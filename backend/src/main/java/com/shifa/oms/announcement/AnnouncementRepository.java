package com.shifa.oms.announcement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Persistence for staff announcement banners (FEATURE-ROADMAP §8.4).
 */
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Active announcements shown to staff, newest first. */
    List<Announcement> findByActiveTrueOrderByCreatedAtDesc();

    /** Every announcement (admin management view), newest first. */
    List<Announcement> findAllByOrderByCreatedAtDesc();
}
