package io.stagepass.booking;

import java.util.List;

public final class OfferNext implements WaitlistOfferMode {
    public String name() { return "OFFER_NEXT"; }
    public String next(List<String> users, String expired) { return users.stream().filter(id -> !id.equals(expired)).findFirst().orElse(null); }
}
