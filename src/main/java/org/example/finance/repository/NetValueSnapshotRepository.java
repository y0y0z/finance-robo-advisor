package org.example.finance.repository;

import org.example.finance.model.NetValueSnapshot;
import org.example.finance.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NetValueSnapshotRepository extends JpaRepository<NetValueSnapshot, Long> {
    List<NetValueSnapshot> findByUserOrderBySnapshotDateAsc(User user);
    Optional<NetValueSnapshot> findByUserAndSnapshotDate(User user, LocalDate date);
}
