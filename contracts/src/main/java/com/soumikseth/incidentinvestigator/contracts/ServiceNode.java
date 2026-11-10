package com.soumikseth.incidentinvestigator.contracts;

import java.util.List;

public record ServiceNode(String name, String status, List<String> dependencies) {
}
