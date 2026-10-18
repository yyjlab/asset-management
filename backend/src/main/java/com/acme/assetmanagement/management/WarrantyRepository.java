package com.acme.assetmanagement.management;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
interface WarrantyRepository extends JpaRepository<AssetWarranty, Long>, JpaSpecificationExecutor<AssetWarranty> {
    Optional<AssetWarranty> findByAssetId(Long assetId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select w from AssetWarranty w where w.assetId = :id") Optional<AssetWarranty> lockedAsset(@Param("id") Long id);
}
