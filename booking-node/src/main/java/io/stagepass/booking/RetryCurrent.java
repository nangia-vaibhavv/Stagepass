package io.stagepass.booking;

import java.util.List;

public final class RetryCurrent implements WaitlistOfferMode {
    public String name() { return "RETRY_CURRENT"; }
    public String next(List<String> users, String expired) { return users.contains(expired) ? expired : users.stream().findFirst().orElse(null); }
}
