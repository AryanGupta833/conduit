# SFTP plugin

`SFTP` lists remote directories, downloads files to the managed execution workspace, and uploads workspace files to an SFTP server. It uses Apache MINA SSHD and never executes remote commands.

## Security and limits

- Configure `password` as a workflow credential reference, for example `{"$credential":"vendor-sftp-password"}`. Plaintext inline secrets are rejected by the common configuration guard.
- Pin the server with `hostKeyFingerprint`, in OpenSSH format (`SHA256:...`). Unknown or changed host keys fail closed; the plugin does not accept-all or automatically trust keys.
- Public network hosts are allowed by default. Set `allowPrivateNetwork: true` only when the workflow is meant to contact an approved private SFTP service.
- Port, connection/authentication timeout, directory results, and file transfer sizes are bounded. `maxBytes` is capped at 100 MiB and workspace quota checks still apply.
- File transfers use workspace-relative `localPath` values and the managed workspace APIs. Paths cannot escape the execution workspace.
- Uploads first write to a task-idempotency-specific temporary remote name and then rename it. Failed uploads remove the temporary file when possible.
- The plugin redacts configured credentials through Conduit's normal task-result and log redaction pipeline.

## Operations

### Download

```json
{
  "operation": "DOWNLOAD",
  "host": "sftp.partner.example",
  "port": 22,
  "username": "conduit-ingest",
  "password": {"$credential": "partner-sftp-password"},
  "hostKeyFingerprint": "SHA256:REPLACE_WITH_VERIFIED_43_CHARACTER_FINGERPRINT",
  "remotePath": "/drop/daily-orders.json",
  "localPath": "partner/orders.json",
  "maxBytes": 26214400,
  "timeoutSeconds": 20,
  "overwrite": true
}
```

### Upload

Set `operation` to `UPLOAD`, use a workspace-relative `localPath` that exists, and set `remotePath` to the remote destination. Upload overwrites are opt-in (`overwrite: true`).

### List

Set `operation` to `LIST` and provide `remotePath`; output is a bounded JSON array with names, directory flags, sizes, and modification times.

## Example workflow shapes

The frontend's **Vendor SFTP order intake** workflow downloads a JSON batch and lists the drop directory in parallel, joins those results at a human approval task, then archives approved files. **SFTP dual-feed reconciliation** downloads order and inventory feeds in parallel, waits for both before human review, then archives each feed after approval. Both templates contain placeholders: configure the endpoint, verified host fingerprint, username, and workflow credential before execution.

The sample workflows are templates rather than auto-created database rows. Their remote paths and formats are examples; adapt them to an SFTP account you control or have authorization to use.
