package com.kevinabura.ledgerguard.application;

public record ReverseTransactionCommand(String externalReference, String reason) {}
