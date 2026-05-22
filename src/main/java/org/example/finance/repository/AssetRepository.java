package org.example.finance.repository;

import org.example.finance.model.Asset;
import org.example.finance.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetRepository extends JpaRepository<Asset, Long> {
    List<Asset> findByUser(User user);
    Asset findByUserAndCode(User user, String code);
}
