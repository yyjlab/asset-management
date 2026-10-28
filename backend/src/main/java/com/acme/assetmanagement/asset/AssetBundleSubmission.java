package com.acme.assetmanagement.asset;

import jakarta.persistence.*;
import java.util.*;

/** Completed submissions only. A network retry must never recreate an already committed set. */
@Entity @Table(name = "asset_bundle_submissions")
class AssetBundleSubmission {
    @Id @Column(length = 36) String requestKey;
    @Column(nullable = false) Long ownerId;
    @Column(nullable = false, length = 64) String fingerprint;
    @Column(nullable = false) Long computerId;
    @ElementCollection
    @CollectionTable(name = "asset_bundle_display_ids", joinColumns = @JoinColumn(name = "request_key"))
    @OrderColumn(name = "display_order") @Column(nullable = false) List<Long> displayIds = new ArrayList<>();
    protected AssetBundleSubmission() {}
}
