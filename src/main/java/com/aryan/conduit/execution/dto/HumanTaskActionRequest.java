package com.aryan.conduit.execution.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record HumanTaskActionRequest(String comment, JsonNode response) { }
