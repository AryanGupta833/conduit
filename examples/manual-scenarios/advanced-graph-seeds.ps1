# Imports realistic branched manual scenarios into a running Conduit instance.
# Run with PowerShell; if $headers is not supplied, the script prompts for an
# account and reads the password securely (it is never saved in this file).
$ErrorActionPreference = 'Stop'
$baseUrl = 'http://localhost:8080'
if (-not $headers.Authorization) {
    $email = Read-Host 'Conduit account email (default: aryangupta@gmail.com)'
    if ([string]::IsNullOrWhiteSpace($email)) { $email = 'aryangupta@gmail.com' }
    $securePassword = Read-Host 'Conduit account password' -AsSecureString
    $credential = [pscredential]::new($email, $securePassword)
    $loginBody = @{ email=$credential.UserName; password=$credential.GetNetworkCredential().Password } | ConvertTo-Json
    try {
        $session = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody -TimeoutSec 15
        $headers = @{ Authorization="Bearer $($session.accessToken)" }
    } catch {
        throw "Conduit sign-in failed at $baseUrl. Start the backend and verify the account credentials before importing."
    } finally {
        $loginBody = $null
        $credential = $null
        if ($securePassword) { $securePassword.Dispose() }
    }
}

function New-ScenarioTask($name, $label, $plugin, $config, $x, $y, $join = 'ALL_PARENTS', $retries = 0) {
    return @{ name=$name; displayName=$label; pluginType=$plugin; configurationJson=($config | ConvertTo-Json -Depth 60 -Compress); timeoutSeconds=300; maxRetries=$retries; joinCondition=$join; xPosition=$x; yPosition=$y }
}
function New-ScenarioEdge($parent, $child, $condition = 'ON_SUCCESS', $expression = $null) {
    return @{ parentTask=$parent; childTask=$child; condition=$condition; expression=$expression }
}
function Import-Scenario($definition) {
    $id = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/workflows/import" -Headers $headers -ContentType 'application/json' -Body ($definition | ConvertTo-Json -Depth 80 -Compress) -TimeoutSec 30
    $validation = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/workflows/$id/validate" -Headers $headers -TimeoutSec 20
    $graph = Invoke-RestMethod -Uri "$baseUrl/api/workflows/$id/graph" -Headers $headers -TimeoutSec 15
    return @{ id=$id; name=$definition.name; tasks=@($graph.nodes).Count; edges=@($graph.edges).Count; valid=$validation.valid; errors=@($validation.errors).Count; warnings=@($validation.warnings).Count }
}

function Get-ScenarioReport($id, $name, $alreadyExisted = $false) {
    $validation = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/workflows/$id/validate" -Headers $headers -TimeoutSec 20
    $graph = Invoke-RestMethod -Uri "$baseUrl/api/workflows/$id/graph" -Headers $headers -TimeoutSec 15
    return @{ id=$id; name=$name; tasks=@($graph.nodes).Count; edges=@($graph.edges).Count; valid=$validation.valid; errors=@($validation.errors).Count; warnings=@($validation.warnings).Count; alreadyExisted=$alreadyExisted }
}

function Find-WorkflowByName($workflows, $targetName) {
    foreach ($item in $workflows) {
        if ([string]::Equals([string]$item.name, [string]$targetName, [System.StringComparison]::Ordinal)) {
            return $item
        }
    }
    return $null
}

$labRoot = Join-Path $env:TEMP 'conduit-manual-release-lab'
$releasePath = 'releases/${variables.releaseVersion}/spring-petclinic'
$scenarios = @()

# Real public catalog, buyer, and cart reads fan out, join into a packet,
# route high-risk cases through a human, then fan out to durable sinks.
$scenarios += @{
    schemaVersion=1; name='Live Scenario — Retail Catalog Intelligence and Human Pricing Review'; cronExpression=$null
    tasks=@(
        (New-ScenarioTask 'initialize_catalog_case' 'Create review case and policy thresholds' 'SET_VARIABLES' @{variables=@{caseId='CAT-2026-1044';productId=1;buyerId=1;cartId=1;manualPriceThreshold=100;manualStockThreshold=10}} 80 300),
        (New-ScenarioTask 'fetch_live_product' 'Read live product price, stock, rating and reviews from DummyJSON' 'HTTP' @{url='https://dummyjson.com/products/${variables.productId}';method='GET';allowPrivateNetwork=$false} 320 100),
        (New-ScenarioTask 'fetch_sample_buyer' 'Read the buyer profile for the fulfillment case' 'HTTP' @{url='https://dummyjson.com/users/${variables.buyerId}';method='GET';allowPrivateNetwork=$false} 320 300),
        (New-ScenarioTask 'fetch_open_cart' 'Read cart quantity and current order total' 'HTTP' @{url='https://dummyjson.com/carts/${variables.cartId}';method='GET';allowPrivateNetwork=$false} 320 500),
        (New-ScenarioTask 'parse_product_record' 'Parse the live product record' 'JSON_TRANSFORM' @{input='${tasks.fetch_live_product.output}';operation='PARSE'} 560 100),
        (New-ScenarioTask 'parse_buyer_record' 'Parse the live buyer record' 'JSON_TRANSFORM' @{input='${tasks.fetch_sample_buyer.output}';operation='PARSE'} 560 300),
        (New-ScenarioTask 'parse_cart_record' 'Parse the live cart record' 'JSON_TRANSFORM' @{input='${tasks.fetch_open_cart.output}';operation='PARSE'} 560 500),
        (New-ScenarioTask 'assemble_offer_packet' 'Join product, buyer and cart data into one case packet' 'JSON_TRANSFORM' @{operation='STRINGIFY';input=@{source='DummyJSON public product, user and cart API';caseId='${variables.caseId}';product='${tasks.parse_product_record.output}';buyer='${tasks.parse_buyer_record.output}';cart='${tasks.parse_cart_record.output}'}} 800 300),
        (New-ScenarioTask 'derive_catalog_risk' 'Derive typed price, rating, stock and buyer signals' 'SET_VARIABLES' @{variables=@{productPrice='${tasks.parse_product_record.output.price}';productRating='${tasks.parse_product_record.output.rating}';productStock='${tasks.parse_product_record.output.stock}';productTitle='${tasks.parse_product_record.output.title}';buyerName='${tasks.parse_buyer_record.output.firstName}';cartTotal='${tasks.parse_cart_record.output.total}'}} 1040 300),
        (New-ScenarioTask 'human_catalog_exception_review' 'Human approval for high price, low stock or poor-rated items' 'HUMAN_TASK' @{type='APPROVAL';title='Catalog exception: ${variables.productTitle}';description='Review live pricing, available stock, customer reviews and basket total. Approve the merchandising or replenishment action.';assignee='aryangupta@gmail.com';timeoutSeconds=86400;timeoutPolicy='FAIL';metadata=@{caseId='${variables.caseId}';price='${variables.productPrice}';stock='${variables.productStock}';rating='${variables.productRating}'}} 1280 150),
        (New-ScenarioTask 'automatic_catalog_clearance' 'Record the low-risk policy route' 'LOG' @{} 1280 450),
        (New-ScenarioTask 'authorize_catalog_action' 'Continue from human approval or automatic clearance' 'SET_VARIABLES' @{variables=@{catalogReviewCompleted=$true;catalogCase='${variables.caseId}'}} 1520 300 'ANY_PARENT'),
        (New-ScenarioTask 'ensure_catalog_audit_table' 'Prepare the durable catalog review audit table' 'POSTGRESQL' @{url=@{'$credential'='catalog.postgres.url'};username=@{'$credential'='catalog.postgres.username'};password=@{'$credential'='catalog.postgres.password'};query='CREATE TABLE IF NOT EXISTS conduit_catalog_review (case_id text PRIMARY KEY, product_id integer NOT NULL, title text NOT NULL, price numeric NOT NULL, stock integer NOT NULL, rating numeric NOT NULL, review_packet jsonb NOT NULL, reviewed_at timestamptz NOT NULL DEFAULT now())';parameters=@()} 1760 300),
        (New-ScenarioTask 'persist_catalog_review' 'Upsert the decision and complete source packet idempotently' 'POSTGRESQL' @{url=@{'$credential'='catalog.postgres.url'};username=@{'$credential'='catalog.postgres.username'};password=@{'$credential'='catalog.postgres.password'};query='INSERT INTO conduit_catalog_review (case_id, product_id, title, price, stock, rating, review_packet) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (case_id) DO UPDATE SET price=EXCLUDED.price,stock=EXCLUDED.stock,rating=EXCLUDED.rating,review_packet=EXCLUDED.review_packet,reviewed_at=now()';parameters=@('${variables.caseId}','${variables.productId}','${variables.productTitle}','${variables.productPrice}','${variables.productStock}','${variables.productRating}','${tasks.assemble_offer_packet.output} ')} 2000 300),
        (New-ScenarioTask 'cache_catalog_offer' 'Cache the review packet for downstream merchandising' 'REDIS' @{host='localhost';port=6379;operation='SET';key='conduit:catalog:${variables.caseId}';value='${tasks.assemble_offer_packet.output} ';ttlSeconds=86400} 2240 100),
        (New-ScenarioTask 'write_catalog_artifact' 'Write the review packet into the managed execution workspace' 'FILE_WRITE' @{workspaceRoot='managed';path='retail/catalog/${variables.caseId}.json';content='${tasks.assemble_offer_packet.output} ';encoding='UTF-8';createDirectories=$true;overwrite=$true} 2240 250),
        (New-ScenarioTask 'archive_catalog_packet' 'Archive the reviewed packet to the configured S3 bucket' 'S3_UPLOAD' @{bucket='conduit-manual-retail-reviews';key='catalog/${variables.caseId}/review-packet.json';content='${tasks.assemble_offer_packet.output} ';contentType='application/json'} 2240 400),
        (New-ScenarioTask 'publish_catalog_event' 'Publish the completed catalog decision for consumers' 'KAFKA_PRODUCER' @{topic='catalog.review.completed.v1';key='${variables.caseId}';message=@{caseId='${variables.caseId}';product='${tasks.parse_product_record.output}';buyer='${tasks.parse_buyer_record.output}'};timeoutSeconds=30} 2240 550),
        (New-ScenarioTask 'notify_catalog_operations' 'Send reviewed result to the configured operations webhook' 'WEBHOOK' @{url='https://httpbin.org/post';method='POST';headers=@{'Content-Type'='application/json'};body=@{caseId='${variables.caseId}';decision='REVIEWED';packet='${tasks.assemble_offer_packet.output}'};allowPrivateNetwork=$false} 2240 700),
        (New-ScenarioTask 'close_catalog_case' 'Close after all persistence and notification branches complete' 'LOG' @{} 2480 400)
    )
    dependencies=@(
        (New-ScenarioEdge 'initialize_catalog_case' 'fetch_live_product'),(New-ScenarioEdge 'initialize_catalog_case' 'fetch_sample_buyer'),(New-ScenarioEdge 'initialize_catalog_case' 'fetch_open_cart'),
        (New-ScenarioEdge 'fetch_live_product' 'parse_product_record'),(New-ScenarioEdge 'fetch_sample_buyer' 'parse_buyer_record'),(New-ScenarioEdge 'fetch_open_cart' 'parse_cart_record'),
        (New-ScenarioEdge 'parse_product_record' 'assemble_offer_packet'),(New-ScenarioEdge 'parse_buyer_record' 'assemble_offer_packet'),(New-ScenarioEdge 'parse_cart_record' 'assemble_offer_packet'),
        (New-ScenarioEdge 'assemble_offer_packet' 'derive_catalog_risk'),
        (New-ScenarioEdge 'derive_catalog_risk' 'human_catalog_exception_review' 'EXPRESSION' 'variables.productPrice >= 100 || variables.productStock <= 10 || variables.productRating < 3'),
        (New-ScenarioEdge 'derive_catalog_risk' 'automatic_catalog_clearance' 'EXPRESSION' 'variables.productPrice < 100 && variables.productStock > 10 && variables.productRating >= 3'),
        (New-ScenarioEdge 'human_catalog_exception_review' 'authorize_catalog_action'),(New-ScenarioEdge 'automatic_catalog_clearance' 'authorize_catalog_action'),
        (New-ScenarioEdge 'authorize_catalog_action' 'ensure_catalog_audit_table'),(New-ScenarioEdge 'ensure_catalog_audit_table' 'persist_catalog_review'),
        (New-ScenarioEdge 'persist_catalog_review' 'cache_catalog_offer'),(New-ScenarioEdge 'persist_catalog_review' 'write_catalog_artifact'),(New-ScenarioEdge 'persist_catalog_review' 'archive_catalog_packet'),(New-ScenarioEdge 'persist_catalog_review' 'publish_catalog_event'),(New-ScenarioEdge 'persist_catalog_review' 'notify_catalog_operations'),
        (New-ScenarioEdge 'cache_catalog_offer' 'close_catalog_case'),(New-ScenarioEdge 'write_catalog_artifact' 'close_catalog_case'),(New-ScenarioEdge 'archive_catalog_packet' 'close_catalog_case'),(New-ScenarioEdge 'publish_catalog_event' 'close_catalog_case'),(New-ScenarioEdge 'notify_catalog_operations' 'close_catalog_case')
    )
}

# Real GitHub API observations fan out, join for maintainer risk routing, then persist and publish.
$scenarios += @{
    schemaVersion=1; name='Live Scenario — GitHub Repository Intelligence and Maintainer Escalation'; cronExpression=$null
    tasks=@(
        (New-ScenarioTask 'initialize_repository_watch' 'Set repository identity and review threshold' 'SET_VARIABLES' @{variables=@{repository='spring-projects/spring-petclinic';watchId='OSS-WATCH-2026-1044';maintainerReviewThreshold=50}} 80 300),
        (New-ScenarioTask 'fetch_github_repository' 'Read live stars, forks, watchers and repository metadata' 'HTTP' @{url='https://api.github.com/repos/spring-projects/spring-petclinic';method='GET';allowPrivateNetwork=$false} 320 100),
        (New-ScenarioTask 'fetch_open_issue_signal' 'Query the live open-issue count from GitHub Search' 'HTTP' @{url='https://api.github.com/search/issues?q=repo%3Aspring-projects%2Fspring-petclinic%20is%3Aissue%20is%3Aopen&per_page=1';method='GET';allowPrivateNetwork=$false} 320 300),
        (New-ScenarioTask 'fetch_latest_commit' 'Read the latest public commit metadata' 'HTTP' @{url='https://api.github.com/repos/spring-projects/spring-petclinic/commits?per_page=1';method='GET';allowPrivateNetwork=$false} 320 500),
        (New-ScenarioTask 'parse_github_repository' 'Parse current repository metrics' 'JSON_TRANSFORM' @{input='${tasks.fetch_github_repository.output}';operation='PARSE'} 560 100),
        (New-ScenarioTask 'parse_issue_search' 'Parse total_count and issue search response' 'JSON_TRANSFORM' @{input='${tasks.fetch_open_issue_signal.output}';operation='PARSE'} 560 300),
        (New-ScenarioTask 'parse_latest_commit' 'Parse latest commit response' 'JSON_TRANSFORM' @{input='${tasks.fetch_latest_commit.output}';operation='PARSE'} 560 500),
        (New-ScenarioTask 'compile_maintainer_brief' 'Join repository, backlog and commit data into one evidence packet' 'JSON_TRANSFORM' @{operation='STRINGIFY';input=@{repository='${tasks.parse_github_repository.output}';openIssues='${tasks.parse_issue_search.output}';latestCommit='${tasks.parse_latest_commit.output}';source='GitHub public REST API'}} 800 300),
        (New-ScenarioTask 'derive_repository_risk' 'Set typed issue and popularity values for route evaluation' 'SET_VARIABLES' @{variables=@{openIssueCount='${tasks.parse_issue_search.output.total_count}';stars='${tasks.parse_github_repository.output.stargazers_count}';watchers='${tasks.parse_github_repository.output.subscribers_count}'}} 1040 300),
        (New-ScenarioTask 'human_maintainer_triage' 'Ask a maintainer to triage a backlog above policy threshold' 'HUMAN_TASK' @{type='REVIEW';title='Triage ${variables.openIssueCount} open issues for ${variables.repository}';description='Review the live issue backlog, repository popularity and latest commit before deciding whether to create maintainer work.';assignee='aryangupta@gmail.com';timeoutSeconds=86400;timeoutPolicy='FAIL';metadata=@{watchId='${variables.watchId}';openIssues='${variables.openIssueCount}';stars='${variables.stars}'}} 1280 150),
        (New-ScenarioTask 'routine_maintainer_watch' 'Record a routine backlog below the escalation threshold' 'LOG' @{} 1280 450),
        (New-ScenarioTask 'authorize_maintainer_digest' 'Continue after maintainer triage or routine clearance' 'SET_VARIABLES' @{variables=@{watchComplete=$true}} 1520 300 'ANY_PARENT'),
        (New-ScenarioTask 'ensure_repository_watch_table' 'Prepare durable repository watch history' 'POSTGRESQL' @{url=@{'$credential'='oss.postgres.url'};username=@{'$credential'='oss.postgres.username'};password=@{'$credential'='oss.postgres.password'};query='CREATE TABLE IF NOT EXISTS conduit_repository_watch (watch_id text PRIMARY KEY, repository text NOT NULL, open_issues integer NOT NULL, stars integer NOT NULL, report jsonb NOT NULL, observed_at timestamptz NOT NULL DEFAULT now())';parameters=@()} 1760 300),
        (New-ScenarioTask 'persist_repository_watch' 'Upsert live GitHub metrics for trend analysis' 'POSTGRESQL' @{url=@{'$credential'='oss.postgres.url'};username=@{'$credential'='oss.postgres.username'};password=@{'$credential'='oss.postgres.password'};query='INSERT INTO conduit_repository_watch (watch_id, repository, open_issues, stars, report) VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT (watch_id) DO UPDATE SET open_issues=EXCLUDED.open_issues,stars=EXCLUDED.stars,report=EXCLUDED.report,observed_at=now()';parameters=@('${variables.watchId}','${variables.repository}','${variables.openIssueCount}','${variables.stars}','${tasks.compile_maintainer_brief.output} ')} 2000 300),
        (New-ScenarioTask 'cache_repository_signal' 'Cache the latest signal for a reliability dashboard' 'REDIS' @{host='localhost';port=6379;operation='SET';key='conduit:oss-watch:${variables.repository}';value='${tasks.compile_maintainer_brief.output} ';ttlSeconds=21600} 2240 100),
        (New-ScenarioTask 'archive_maintainer_brief' 'Archive evidence packet for later repository trend comparisons' 'S3_UPLOAD' @{bucket='conduit-manual-oss-intelligence';key='repository-watch/${variables.watchId}.json';content='${tasks.compile_maintainer_brief.output} ';contentType='application/json'} 2240 250),
        (New-ScenarioTask 'publish_repository_signal' 'Publish repository signal to engineering analytics consumers' 'KAFKA_PRODUCER' @{topic='engineering.repository.watch.v1';key='${variables.repository}';message=@{repository='${variables.repository}';openIssueCount='${variables.openIssueCount}';stars='${variables.stars}';watchId='${variables.watchId}'};timeoutSeconds=30} 2240 400),
        (New-ScenarioTask 'notify_maintainer_channel' 'Send the reviewed repository brief to an operations webhook' 'WEBHOOK' @{url='https://httpbin.org/post';method='POST';headers=@{'Content-Type'='application/json'};body=@{repository='${variables.repository}';openIssueCount='${variables.openIssueCount}';watchId='${variables.watchId}'};allowPrivateNetwork=$false} 2240 550),
        (New-ScenarioTask 'close_repository_watch' 'Close only after persistence and publication branches finish' 'LOG' @{} 2480 325)
    )
    dependencies=@(
        (New-ScenarioEdge 'initialize_repository_watch' 'fetch_github_repository'),(New-ScenarioEdge 'initialize_repository_watch' 'fetch_open_issue_signal'),(New-ScenarioEdge 'initialize_repository_watch' 'fetch_latest_commit'),
        (New-ScenarioEdge 'fetch_github_repository' 'parse_github_repository'),(New-ScenarioEdge 'fetch_open_issue_signal' 'parse_issue_search'),(New-ScenarioEdge 'fetch_latest_commit' 'parse_latest_commit'),
        (New-ScenarioEdge 'parse_github_repository' 'compile_maintainer_brief'),(New-ScenarioEdge 'parse_issue_search' 'compile_maintainer_brief'),(New-ScenarioEdge 'parse_latest_commit' 'compile_maintainer_brief'),
        (New-ScenarioEdge 'compile_maintainer_brief' 'derive_repository_risk'),
        (New-ScenarioEdge 'derive_repository_risk' 'human_maintainer_triage' 'EXPRESSION' 'variables.openIssueCount >= variables.maintainerReviewThreshold'),
        (New-ScenarioEdge 'derive_repository_risk' 'routine_maintainer_watch' 'EXPRESSION' 'variables.openIssueCount < variables.maintainerReviewThreshold'),
        (New-ScenarioEdge 'human_maintainer_triage' 'authorize_maintainer_digest'),(New-ScenarioEdge 'routine_maintainer_watch' 'authorize_maintainer_digest'),
        (New-ScenarioEdge 'authorize_maintainer_digest' 'ensure_repository_watch_table'),(New-ScenarioEdge 'ensure_repository_watch_table' 'persist_repository_watch'),
        (New-ScenarioEdge 'persist_repository_watch' 'cache_repository_signal'),(New-ScenarioEdge 'persist_repository_watch' 'archive_maintainer_brief'),(New-ScenarioEdge 'persist_repository_watch' 'publish_repository_signal'),(New-ScenarioEdge 'persist_repository_watch' 'notify_maintainer_channel'),
        (New-ScenarioEdge 'cache_repository_signal' 'close_repository_watch'),(New-ScenarioEdge 'archive_maintainer_brief' 'close_repository_watch'),(New-ScenarioEdge 'publish_repository_signal' 'close_repository_watch'),(New-ScenarioEdge 'notify_maintainer_channel' 'close_repository_watch')
    )
}

# S3 settlement evidence and live FX rates run in parallel, then branch by payout value.
$scenarios += @{
    schemaVersion=1; name='Live Scenario — Partner FX Settlement Reconciliation and Payout Approval'; cronExpression=$null
    tasks=@(
        (New-ScenarioTask 'initialize_partner_batch' 'Set partner batch identity and finance threshold' 'SET_VARIABLES' @{variables=@{partnerId='northstar-logistics';batchId='SETTLE-2026-10-04-001';settlementDate='2026-10-04';manualApprovalUsd=10000}} 80 300),
        (New-ScenarioTask 'download_partner_statement' 'Download partner settlement JSON from inbound S3' 'S3_DOWNLOAD' @{bucket='conduit-partner-settlement-inbound';key='northstar/${variables.settlementDate}/${variables.batchId}.json';workspaceRoot='managed';destinationPath='partner/inbound/settlement.json';createDirectories=$true;overwrite=$true} 320 150),
        (New-ScenarioTask 'fetch_live_usd_fx_rates' 'Fetch current USD exchange rates from the public FX API' 'HTTP' @{url='https://open.er-api.com/v6/latest/USD';method='GET';allowPrivateNetwork=$false} 320 450),
        (New-ScenarioTask 'read_partner_statement' 'Read the downloaded statement from the execution workspace' 'FILE_READ' @{workspaceRoot='managed';path='partner/inbound/settlement.json';encoding='UTF-8'} 560 150),
        (New-ScenarioTask 'parse_partner_statement' 'Parse batch total, transaction count and settlement lines' 'JSON_TRANSFORM' @{input='${tasks.read_partner_statement.output}';operation='PARSE'} 800 150),
        (New-ScenarioTask 'parse_fx_rate_table' 'Parse live USD-denominated exchange rates' 'JSON_TRANSFORM' @{input='${tasks.fetch_live_usd_fx_rates.output}';operation='PARSE'} 560 450),
        (New-ScenarioTask 'assemble_reconciliation_evidence' 'Join partner statement and live FX response' 'JSON_TRANSFORM' @{operation='STRINGIFY';input=@{partner='${tasks.parse_partner_statement.output}';fxRates='${tasks.parse_fx_rate_table.output}';batchId='${variables.batchId}';source='Partner S3 statement plus public ExchangeRate API'}} 1040 300),
        (New-ScenarioTask 'derive_payout_controls' 'Derive typed payout total, transaction count and EUR rate' 'SET_VARIABLES' @{variables=@{settlementTotalUsd='${tasks.parse_partner_statement.output.totalUsd}';transactionCount='${tasks.parse_partner_statement.output.transactionCount}';eurPerUsd='${tasks.parse_fx_rate_table.output.rates.EUR}'}} 1280 300),
        (New-ScenarioTask 'human_high_value_payout_approval' 'Finance approval for high-value partner settlement' 'HUMAN_TASK' @{type='APPROVAL';title='Approve ${variables.batchId} payout of USD ${variables.settlementTotalUsd}';description='Compare the partner statement totals with the current USD/EUR rate and approve or reject the payout.';assignee='aryangupta@gmail.com';timeoutSeconds=86400;timeoutPolicy='FAIL';metadata=@{batchId='${variables.batchId}';totalUsd='${variables.settlementTotalUsd}';eurPerUsd='${variables.eurPerUsd}'}} 1520 150),
        (New-ScenarioTask 'auto_clear_standard_payout' 'Record the standard-value policy route' 'LOG' @{} 1520 450),
        (New-ScenarioTask 'authorize_partner_payout' 'Continue after finance approval or standard-value clearance' 'SET_VARIABLES' @{variables=@{payoutAuthorizedRecorded=$true}} 1760 300 'ANY_PARENT'),
        (New-ScenarioTask 'ensure_partner_ledger_table' 'Prepare idempotent partner settlement ledger table' 'POSTGRESQL' @{url=@{'$credential'='settlement.postgres.url'};username=@{'$credential'='settlement.postgres.username'};password=@{'$credential'='settlement.postgres.password'};query='CREATE TABLE IF NOT EXISTS partner_settlement_audit (batch_id text PRIMARY KEY, partner_id text NOT NULL, settlement_date date NOT NULL, total_usd numeric NOT NULL, transaction_count integer NOT NULL, fx_eur_per_usd numeric NOT NULL, evidence jsonb NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now())';parameters=@()} 2000 300),
        (New-ScenarioTask 'persist_partner_ledger' 'Upsert reconciled totals and evidence into PostgreSQL' 'POSTGRESQL' @{url=@{'$credential'='settlement.postgres.url'};username=@{'$credential'='settlement.postgres.username'};password=@{'$credential'='settlement.postgres.password'};query='INSERT INTO partner_settlement_audit (batch_id, partner_id, settlement_date, total_usd, transaction_count, fx_eur_per_usd, evidence) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (batch_id) DO UPDATE SET total_usd=EXCLUDED.total_usd,transaction_count=EXCLUDED.transaction_count,fx_eur_per_usd=EXCLUDED.fx_eur_per_usd,evidence=EXCLUDED.evidence,recorded_at=now()';parameters=@('${variables.batchId}','${variables.partnerId}','${variables.settlementDate}','${variables.settlementTotalUsd}','${variables.transactionCount}','${variables.eurPerUsd}','${tasks.assemble_reconciliation_evidence.output} ')} 2240 300),
        (New-ScenarioTask 'write_reconciliation_artifact' 'Write reconciled evidence to the managed execution workspace' 'FILE_WRITE' @{workspaceRoot='managed';path='partner/outbound/${variables.batchId}-reconciled.json';content='${tasks.assemble_reconciliation_evidence.output} ';encoding='UTF-8';createDirectories=$true;overwrite=$true} 2480 100),
        (New-ScenarioTask 'archive_reconciliation_evidence' 'Archive the reconciliation packet to outbound S3' 'S3_UPLOAD' @{bucket='conduit-partner-settlement-outbound';key='northstar/${variables.settlementDate}/${variables.batchId}-reconciled.json';content='${tasks.assemble_reconciliation_evidence.output} ';contentType='application/json'} 2480 250),
        (New-ScenarioTask 'cache_partner_delivery_marker' 'Cache a week-long duplicate-delivery marker' 'REDIS' @{host='localhost';port=6379;operation='SET';key='conduit:settlement:${variables.batchId}';value='AUTHORIZED:${variables.partnerId}';ttlSeconds=604800} 2480 400),
        (New-ScenarioTask 'wait_for_payout_window' 'Apply a short release cooldown after durable writes' 'SLEEP' @{} 2720 300),
        (New-ScenarioTask 'publish_settlement_event' 'Publish payout-ready event for finance and ledger consumers' 'KAFKA_PRODUCER' @{topic='partner.settlement.reconciled.v1';key='${variables.batchId}';message=@{batchId='${variables.batchId}';partnerId='${variables.partnerId}';totalUsd='${variables.settlementTotalUsd}';eurPerUsd='${variables.eurPerUsd}'};timeoutSeconds=30} 2960 220),
        (New-ScenarioTask 'notify_finance_operations' 'Send payout-ready details to the operations webhook' 'WEBHOOK' @{url='https://httpbin.org/post';method='POST';headers=@{'Content-Type'='application/json'};body=@{batchId='${variables.batchId}';partnerId='${variables.partnerId}';totalUsd='${variables.settlementTotalUsd}'};allowPrivateNetwork=$false} 2960 400),
        (New-ScenarioTask 'complete_partner_batch' 'Record completion after both release notifications' 'LOG' @{} 3200 300)
    )
    dependencies=@(
        (New-ScenarioEdge 'initialize_partner_batch' 'download_partner_statement'),(New-ScenarioEdge 'initialize_partner_batch' 'fetch_live_usd_fx_rates'),
        (New-ScenarioEdge 'download_partner_statement' 'read_partner_statement'),(New-ScenarioEdge 'read_partner_statement' 'parse_partner_statement'),(New-ScenarioEdge 'fetch_live_usd_fx_rates' 'parse_fx_rate_table'),
        (New-ScenarioEdge 'parse_partner_statement' 'assemble_reconciliation_evidence'),(New-ScenarioEdge 'parse_fx_rate_table' 'assemble_reconciliation_evidence'),(New-ScenarioEdge 'assemble_reconciliation_evidence' 'derive_payout_controls'),
        (New-ScenarioEdge 'derive_payout_controls' 'human_high_value_payout_approval' 'EXPRESSION' 'variables.settlementTotalUsd >= variables.manualApprovalUsd'),(New-ScenarioEdge 'derive_payout_controls' 'auto_clear_standard_payout' 'EXPRESSION' 'variables.settlementTotalUsd < variables.manualApprovalUsd'),
        (New-ScenarioEdge 'human_high_value_payout_approval' 'authorize_partner_payout'),(New-ScenarioEdge 'auto_clear_standard_payout' 'authorize_partner_payout'),(New-ScenarioEdge 'authorize_partner_payout' 'ensure_partner_ledger_table'),(New-ScenarioEdge 'ensure_partner_ledger_table' 'persist_partner_ledger'),
        (New-ScenarioEdge 'persist_partner_ledger' 'write_reconciliation_artifact'),(New-ScenarioEdge 'persist_partner_ledger' 'archive_reconciliation_evidence'),(New-ScenarioEdge 'persist_partner_ledger' 'cache_partner_delivery_marker'),
        (New-ScenarioEdge 'write_reconciliation_artifact' 'wait_for_payout_window'),(New-ScenarioEdge 'archive_reconciliation_evidence' 'wait_for_payout_window'),(New-ScenarioEdge 'cache_partner_delivery_marker' 'wait_for_payout_window'),
        (New-ScenarioEdge 'wait_for_payout_window' 'publish_settlement_event'),(New-ScenarioEdge 'wait_for_payout_window' 'notify_finance_operations'),(New-ScenarioEdge 'publish_settlement_event' 'complete_partner_batch'),(New-ScenarioEdge 'notify_finance_operations' 'complete_partner_batch')
    )
}

# Release pipeline: source checkout, parallel repo checks, image build, parallel canary checks,
# human promotion gate, and an opt-in failure/retry drill.
$scenarios += @{
    schemaVersion=1; name='Scenario Graph — Spring Petclinic Release Candidate and Canary Promotion'; cronExpression=$null
    tasks=@(
        (New-ScenarioTask 'initialize_release' 'Set version, change record and optional failure-drill switch' 'SET_VARIABLES' @{variables=@{releaseVersion='2026.10.04.1';changeTicket='CHG-4821';serviceName='spring-petclinic';runFailureDrill=$false}} 80 300),
        (New-ScenarioTask 'release_policy_gate' 'Check release metadata before touching source or infrastructure' 'SHELL' @{executor='DOCKER';image='alpine:3.20';command='case "${variables.releaseVersion}" in *.*.*) echo "release ${variables.releaseVersion} accepted" ;; *) echo "invalid release version"; exit 2 ;; esac';workingDirectory='/conduit/workspace';workspaceMode='READ_ONLY'} 320 300),
        (New-ScenarioTask 'checkout_petclinic_source' 'Clone the public Spring Petclinic repository' 'GIT' @{operation='CLONE';repository='https://github.com/spring-projects/spring-petclinic.git';workspaceRoot=$labRoot;destinationPath=$releasePath;timeoutSeconds=900} 560 300),
        (New-ScenarioTask 'inspect_release_worktree' 'Check the cloned worktree status' 'GIT' @{operation='STATUS';workspaceRoot=$labRoot;repositoryPath=$releasePath;timeoutSeconds=120} 800 150),
        (New-ScenarioTask 'inspect_recent_commit_history' 'Capture recent commit history for the release record' 'GIT' @{operation='LOG';workspaceRoot=$labRoot;repositoryPath=$releasePath;count=5;timeoutSeconds=120} 800 450),
        (New-ScenarioTask 'build_petclinic_release_image' 'Build a versioned container image from the repository Dockerfile' 'DOCKER_BUILD' @{operation='DOCKER_BUILD';image='conduit/spring-petclinic:${variables.releaseVersion}';workspaceRoot=$labRoot;contextPath=$releasePath;dockerfile='Dockerfile';pull=$false;noCache=$false;timeoutSeconds=900} 1040 300 'ALL_PARENTS'),
        (New-ScenarioTask 'smoke_test_release_container' 'Run a bounded Java runtime check inside the built image' 'DOCKER_RUN' @{operation='DOCKER_RUN';image='conduit/spring-petclinic:${variables.releaseVersion}';command=@('java','-version');environment=@{RELEASE_VERSION='${variables.releaseVersion}';CHANGE_TICKET='${variables.changeTicket}'};timeoutSeconds=60;cpus=1.0;memoryMb=512;pidsLimit=128;networkDisabled=$true;readOnlyRootFilesystem=$true;workspaceMode='READ_ONLY'} 1280 150),
        (New-ScenarioTask 'run_kubernetes_canary_job' 'Run an isolated Kubernetes canary metadata check' 'KUBERNETES_JOB' @{operation='KUBERNETES_JOB';image='alpine:3.20';namespace='default';command=@('sh');args=@('-c','echo canary ${variables.serviceName} ${variables.releaseVersion}; test -n "${variables.changeTicket}"');timeoutSeconds=120;logLines=100;cleanup=$true;readOnlyRootFilesystem=$true;cpuRequest='100m';cpuLimit='500m';memoryRequest='64Mi';memoryLimit='256Mi'} 1280 450),
        (New-ScenarioTask 'probe_public_canary_endpoint' 'Verify the release environment endpoint (replace with staging URL when available)' 'HEALTH_CHECK' @{type='HTTP';url='https://httpbin.org/status/200';expectedStatus=200;timeoutSeconds=10;allowPrivateNetwork=$false} 1520 300 'ALL_PARENTS'),
        (New-ScenarioTask 'human_promote_release' 'Require release-manager approval before announcing promotion' 'HUMAN_TASK' @{type='APPROVAL';title='Promote ${variables.serviceName} ${variables.releaseVersion}?';description='Review source status, image smoke check, Kubernetes canary and staging health result before approving promotion.';assignee='aryangupta@gmail.com';timeoutSeconds=86400;timeoutPolicy='FAIL';metadata=@{changeTicket='${variables.changeTicket}';releaseVersion='${variables.releaseVersion}'}} 1760 180),
        (New-ScenarioTask 'human_release_failure_triage' 'Route build, container, canary or health failures to release operations' 'HUMAN_TASK' @{type='REVIEW';title='Release gate failed for ${variables.releaseVersion}';description='Inspect failed source/build/container/Kubernetes/health evidence and record whether to retry or abort.';assignee='aryangupta@gmail.com';timeoutSeconds=86400;timeoutPolicy='FAIL';metadata=@{changeTicket='${variables.changeTicket}';releaseVersion='${variables.releaseVersion}'}} 1520 520 'ANY_PARENT'),
        (New-ScenarioTask 'announce_release_promotion' 'Send the approved promotion to the configured deployment webhook' 'WEBHOOK' @{url='https://httpbin.org/post';method='POST';headers=@{'Content-Type'='application/json'};body=@{event='RELEASE_APPROVED';service='${variables.serviceName}';version='${variables.releaseVersion}';ticket='${variables.changeTicket}'};allowPrivateNetwork=$false} 2000 100),
        (New-ScenarioTask 'publish_release_event' 'Publish the approved release for deployment consumers' 'KAFKA_PRODUCER' @{topic='release.approved.v1';key='${variables.serviceName}';message=@{service='${variables.serviceName}';version='${variables.releaseVersion}';ticket='${variables.changeTicket}'};timeoutSeconds=30} 2000 260),
        (New-ScenarioTask 'record_release_complete' 'Record completion after both promotion notifications' 'LOG' @{} 2240 180),
        (New-ScenarioTask 'inject_optional_release_failure' 'Optional retry test; enable runFailureDrill to exercise FAIL' 'FAIL' @{} 320 650 'ALL_PARENTS' 1),
        (New-ScenarioTask 'human_failure_drill_review' 'Review the intentionally failed and retried release drill' 'HUMAN_TASK' @{type='REVIEW';title='Review release failure drill ${variables.releaseVersion}';description='This optional path intentionally fails once through the configured retry policy. Confirm the failure branch and retry history.';assignee='aryangupta@gmail.com';timeoutSeconds=86400;timeoutPolicy='FAIL'} 560 650),
        (New-ScenarioTask 'cooldown_after_failure_drill' 'Pause briefly after failure-drill review' 'SLEEP' @{} 800 650),
        (New-ScenarioTask 'log_failure_drill_result' 'Record failure-drill disposition' 'LOG' @{} 1040 650)
    )
    dependencies=@(
        (New-ScenarioEdge 'initialize_release' 'release_policy_gate'),(New-ScenarioEdge 'release_policy_gate' 'checkout_petclinic_source'),
        (New-ScenarioEdge 'checkout_petclinic_source' 'inspect_release_worktree'),(New-ScenarioEdge 'checkout_petclinic_source' 'inspect_recent_commit_history'),
        (New-ScenarioEdge 'inspect_release_worktree' 'build_petclinic_release_image'),(New-ScenarioEdge 'inspect_recent_commit_history' 'build_petclinic_release_image'),
        (New-ScenarioEdge 'build_petclinic_release_image' 'smoke_test_release_container'),(New-ScenarioEdge 'build_petclinic_release_image' 'run_kubernetes_canary_job'),
        (New-ScenarioEdge 'smoke_test_release_container' 'probe_public_canary_endpoint'),(New-ScenarioEdge 'run_kubernetes_canary_job' 'probe_public_canary_endpoint'),
        (New-ScenarioEdge 'probe_public_canary_endpoint' 'human_promote_release'),(New-ScenarioEdge 'probe_public_canary_endpoint' 'human_release_failure_triage' 'ON_FAILURE'),
        (New-ScenarioEdge 'build_petclinic_release_image' 'human_release_failure_triage' 'ON_FAILURE'),(New-ScenarioEdge 'smoke_test_release_container' 'human_release_failure_triage' 'ON_FAILURE'),(New-ScenarioEdge 'run_kubernetes_canary_job' 'human_release_failure_triage' 'ON_FAILURE'),
        (New-ScenarioEdge 'human_promote_release' 'announce_release_promotion'),(New-ScenarioEdge 'human_promote_release' 'publish_release_event'),(New-ScenarioEdge 'announce_release_promotion' 'record_release_complete'),(New-ScenarioEdge 'publish_release_event' 'record_release_complete'),
        (New-ScenarioEdge 'initialize_release' 'inject_optional_release_failure' 'EXPRESSION' 'variables.runFailureDrill == true'),(New-ScenarioEdge 'inject_optional_release_failure' 'human_failure_drill_review' 'ON_FAILURE'),(New-ScenarioEdge 'human_failure_drill_review' 'cooldown_after_failure_drill'),(New-ScenarioEdge 'cooldown_after_failure_drill' 'log_failure_drill_result')
    )
}

$registered = @((Invoke-RestMethod -Uri "$baseUrl/api/plugins" -Headers $headers -TimeoutSec 15) | ForEach-Object { $_.type })
$covered = @($scenarios | ForEach-Object { $_.tasks } | ForEach-Object { $_.pluginType } | Sort-Object -Unique)
$missing = @($registered | Where-Object { $_ -notin $covered })
if ($missing.Count) { throw "Advanced scenarios do not cover: $($missing -join ', ')" }
$workflowResponse = Invoke-RestMethod -Uri "$baseUrl/api/workflows" -Headers $headers -TimeoutSec 15
$existingWorkflows = @($workflowResponse)
# Some PowerShell/HTTP response adapters flatten a JSON array of records into
# one object whose properties are arrays. Rebuild the records before matching.
if ($existingWorkflows.Count -eq 1 -and $workflowResponse.id -is [array] -and $workflowResponse.name -is [array]) {
    $existingWorkflows = @(
        for ($i = 0; $i -lt $workflowResponse.id.Count; $i++) {
            [pscustomobject]@{
                id = $workflowResponse.id[$i]
                name = $workflowResponse.name[$i]
                active = $workflowResponse.active[$i]
            }
        }
    )
}
$reports = @()
foreach ($scenario in $scenarios) {
    $existing = Find-WorkflowByName -workflows $existingWorkflows -targetName ([string]$scenario.name)
    if ($existing) {
        $reports += Get-ScenarioReport $existing.id $scenario.name $true
    } else {
        $reports += Import-Scenario $scenario
    }
}

$invalid = @($reports | Where-Object { -not $_.valid })
if ($invalid.Count) {
    throw "Imported scenario validation failed: $(($invalid | ForEach-Object { $_.name }) -join ', '). Earlier simple workflows were left unchanged."
}

# Deactivate only the exact older lab records created for this user's test account.
$basicNames = @(
    'Manual Lab — Marketplace Order Fulfillment and Audit',
    'Manual Lab — Spring Service Release and Canary Gate',
    'Manual Lab — Partner Settlement Ingestion and Reconciliation',
    'Manual Lab — Payment Capture Failure and Incident Escalation Drill'
)
$deactivated = 0
# Earlier retries of this importer may have created duplicate live scenarios.
# Keep the newest record for each advanced scenario and deactivate older copies.
$scenarioNames = @($scenarios | ForEach-Object { [string]$_.name })
foreach ($workflow in $existingWorkflows) {
    $scenarioMatch = $null
    foreach ($scenarioName in $scenarioNames) {
        if ([string]::Equals([string]$workflow.name, $scenarioName, [System.StringComparison]::Ordinal)) {
            $scenarioMatch = $scenarioName
            break
        }
    }
    $latest = if ($scenarioMatch) { Find-WorkflowByName -workflows $existingWorkflows -targetName $scenarioMatch } else { $null }
    if ($latest -and $workflow.active -and [string]$workflow.id -ne [string]$latest.id) {
        Invoke-RestMethod -Method Put -Uri "$baseUrl/api/workflows/$($workflow.id)/active" -Headers $headers -ContentType 'application/json' -Body (@{active=$false} | ConvertTo-Json -Compress) -TimeoutSec 15 | Out-Null
        $deactivated++
    }
}
foreach ($workflowName in $basicNames) {
    $workflow = Find-WorkflowByName -workflows $existingWorkflows -targetName $workflowName
    if ($workflow -and $workflow.active) {
        Invoke-RestMethod -Method Put -Uri "$baseUrl/api/workflows/$($workflow.id)/active" -Headers $headers -ContentType 'application/json' -Body (@{active=$false} | ConvertTo-Json -Compress) -TimeoutSec 15 | Out-Null
        $deactivated++
    }
}

$reports | ConvertTo-Json -Depth 5
"PLUGIN_TYPES=$($registered.Count); COVERED=$($covered.Count); MISSING=$($missing.Count); WORKFLOWS_DEACTIVATED=$deactivated; EXECUTIONS_STARTED=0"
