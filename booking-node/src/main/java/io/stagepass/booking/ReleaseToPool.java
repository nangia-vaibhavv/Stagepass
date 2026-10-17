package io.stagepass.booking;

import java.util.List;

public final class ReleaseToPool implements WaitlistOfferMode {
    public String name() { return "RELEASE_TO_POOL"; }
    public String next(List<String> users, String expired) { return null; }
}
