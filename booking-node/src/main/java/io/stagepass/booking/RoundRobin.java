package io.stagepass.booking;

import java.util.List;

public final class RoundRobin implements WaitlistOfferMode {
    public String name() { return "ROUND_ROBIN"; }
    public String next(List<String> users, String expired) {
        if (users.isEmpty()) return null;
        int index = users.indexOf(expired);
        return users.get((index + 1 + users.size()) % users.size());
    }
}
