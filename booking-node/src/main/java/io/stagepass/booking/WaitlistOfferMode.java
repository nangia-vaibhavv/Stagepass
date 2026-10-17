package io.stagepass.booking;

import java.util.List;

/** Strategy for selecting the next waitlist member after an offer expires. */
public interface WaitlistOfferMode {
    String name();
    String next(List<String> queuedUsers, String expiredUser);
}
