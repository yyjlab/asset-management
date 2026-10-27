package com.acme.assetmanagement.asset;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {
    @Query("select a from Asset a where a.checkedOut=false and a.assignedUserId is null and (a.assignedTo is null or a.assignedTo='') and a.status.name in ('当前可用','可领用') order by a.assetTag asc")
    List<Asset> oaAvailable(org.springframework.data.domain.Pageable page);
    List<Asset> findTop501ByOwnershipDepartmentOrderByAssetTagAsc(String department);
    List<Asset> findTop501ByOrderByAssetTagAsc();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"company", "model", "category", "status", "location"})
    @Query("select a from Asset a where a.id = :id")
    Optional<Asset> findForRegistrationLink(@Param("id") Long id);
    @EntityGraph(attributePaths = {"model"})
    List<Asset> findTop101ByAssignedUserIdOrderByAssetTagAsc(Long assignedUserId);

    @EntityGraph(attributePaths = {"company", "model", "category", "status", "location"})
    @Query("""
            select a from Asset a where
            (:all = true or (:self = true and a.assignedUserId = :userId)
              or (:department = true and a.ownershipDepartment in :departments))
            and (:search = '' or lower(a.assetTag) like lower(concat('%', :search, '%'))
              or lower(a.name) like lower(concat('%', :search, '%'))
              or lower(coalesce(a.cpu, '')) like lower(concat('%', :search, '%'))
              or lower(coalesce(a.memory, '')) like lower(concat('%', :search, '%'))
              or lower(coalesce(a.storage, '')) like lower(concat('%', :search, '%'))
              or lower(coalesce(a.graphicsCard, '')) like lower(concat('%', :search, '%'))
              or lower(coalesce(a.manufacturerSerialNumber, '')) like lower(concat('%', :search, '%'))
              or lower(coalesce(a.assignedTo, '')) like lower(concat('%', :search, '%'))
              or lower(a.company.name) like lower(concat('%', :search, '%'))
              or lower(a.model.name) like lower(concat('%', :search, '%')))
            order by a.updatedAt desc
            """)
    List<Asset> searchScoped(String search, boolean all, boolean self, Long userId,
                             boolean department, java.util.Set<String> departments);

    @Query("select distinct a from Asset a left join a.imageUrls image where a.imageUrl = :reference or image = :reference")
    List<Asset> findImageOwners(String reference);
    @Query("select count(distinct a.id) from Asset a left join a.imageUrls image where a.imageUrl = :reference or image = :reference")
    long countImageReferences(@Param("reference") String reference);
    @EntityGraph(attributePaths = {"company", "model", "category", "status", "location"})
    @Query("""
            select a from Asset a
            where :search = ''
               or lower(a.assetTag) like lower(concat('%', :search, '%'))
               or lower(a.name) like lower(concat('%', :search, '%'))
               or lower(coalesce(a.cpu, '')) like lower(concat('%', :search, '%'))
               or lower(coalesce(a.memory, '')) like lower(concat('%', :search, '%'))
               or lower(coalesce(a.storage, '')) like lower(concat('%', :search, '%'))
               or lower(coalesce(a.graphicsCard, '')) like lower(concat('%', :search, '%'))
               or lower(coalesce(a.manufacturerSerialNumber, '')) like lower(concat('%', :search, '%'))
               or lower(coalesce(a.assignedTo, '')) like lower(concat('%', :search, '%'))
               or lower(a.company.name) like lower(concat('%', :search, '%'))
               or lower(a.model.name) like lower(concat('%', :search, '%'))
            order by a.updatedAt desc
            """)
    List<Asset> search(@Param("search") String search);

    @Override
    @EntityGraph(attributePaths = {"company", "model", "category", "status", "location"})
    Optional<Asset> findById(Long id);

    @EntityGraph(attributePaths = {"company", "model", "category", "status", "location"})
    Optional<Asset> findByQrToken(String qrToken);
    Optional<Asset> findByAssetTagIgnoreCase(String assetTag);
    Optional<Asset> findTopByAssetTagStartingWithOrderByAssetTagDesc(String prefix);

    boolean existsByAssetTagIgnoreCase(String assetTag);
    boolean existsByAssetTagIgnoreCaseAndIdNot(String assetTag, Long id);
    boolean existsByManufacturerSerialNumberIgnoreCase(String manufacturerSerialNumber);
    boolean existsByManufacturerSerialNumberIgnoreCaseAndIdNot(String manufacturerSerialNumber, Long id);

    @Query("""
            select count(a) from Asset a
            where a.company.id = :lookupId
               or a.model.id = :lookupId
               or a.category.id = :lookupId
               or a.status.id = :lookupId
               or a.location.id = :lookupId
            """)
    long countUsingLookup(@Param("lookupId") Long lookupId);

    @Query("select a.model.id, count(a) from Asset a group by a.model.id")
    java.util.List<Object[]> countModelUsage();

    @Query("select lower(trim(a.cpu)), count(a) from Asset a where a.cpu is not null group by lower(trim(a.cpu))")
    List<Object[]> countCpuUsage();

    @Query("select lower(trim(a.name)), count(a) from Asset a group by lower(trim(a.name))")
    List<Object[]> countNameUsage();
}
