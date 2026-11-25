package com.acme.assetmanagement.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface AssetPreparationRepository extends JpaRepository<AssetPreparation, Long> {
    Optional<AssetPreparation> findByAssetId(Long assetId);
    @Query("select p from AssetPreparation p join fetch p.asset a join fetch a.status where a.id in :ids")
    List<AssetPreparation> forAssets(java.util.Collection<Long> ids);
}
