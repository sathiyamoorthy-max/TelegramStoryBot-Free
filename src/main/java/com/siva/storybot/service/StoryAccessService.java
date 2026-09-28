package com.siva.storybot.service;

import com.siva.storybot.entity.Story;
import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.entity.UserStoryAccess;
import com.siva.storybot.enums.UserRole;
import com.siva.storybot.repository.StoryRepository;
import com.siva.storybot.repository.UserStoryAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryAccessService {

    private final UserStoryAccessRepository userStoryAccessRepository;
    private final StoryRepository storyRepository;

    // =========================================
    // ACCESS CHECK
    // =========================================

    public boolean hasStoryAccess(TelegramUser user, Story story) {

        if (user == null || story == null || user.getRole() == null) {
            return false;
        }

        // OWNER always has access to every story.
        if (user.getRole() == UserRole.OWNER) {
            return true;
        }

        // ADMIN access must come from an OWNER-granted mapping.
        // This closes the promotion loophole where a USER permission granted
        // by another ADMIN could otherwise become ADMIN access after promotion.
        if (user.getRole() == UserRole.ADMIN) {
            return userStoryAccessRepository
                    .existsByTelegramUserAndStoryAndActiveTrueAndGrantedByRole(
                            user,
                            story,
                            UserRole.OWNER);
        }

        // Normal USER can use any active direct mapping granted by OWNER/ADMIN.
        return userStoryAccessRepository
                .existsByTelegramUserAndStoryAndActiveTrue(user, story);
    }

    public boolean hasAssignedStoryAccess(TelegramUser targetUser, Story story) {
        return hasStoryAccess(targetUser, story);
    }

    /**
     * Final listening authorization for this independent deployment.
     *
     * OWNER -> every story.
     * ADMIN -> only OWNER-assigned stories.
     * USER  -> only stories assigned by OWNER/ADMIN.
     *
     * Subscription, global-trial and reward state are intentionally ignored.
     */
    public boolean hasEpisodeAccess(TelegramUser user, Story story) {
        return hasStoryAccess(user, story);
    }

    public long getAssignedStoryCount(TelegramUser user) {

        if (user == null) {
            return 0;
        }

        if (user.getRole() == UserRole.OWNER) {
            return storyRepository.count();
        }

        if (user.getRole() == UserRole.ADMIN) {
            return userStoryAccessRepository
                    .findAllByTelegramUserAndActiveTrue(user)
                    .stream()
                    .filter(access -> access.getGrantedByRole() == UserRole.OWNER)
                    .count();
        }

        return userStoryAccessRepository.countByTelegramUserAndActiveTrue(user);
    }

    // =========================================
    // STORY LISTS FOR CURRENT USER
    // =========================================

    public Page<Story> getCompletedStories(
            TelegramUser user,
            int page,
            int size) {

        // Public catalog: every user can browse every active completed story.
        // Access is checked only when episodes are requested.
        return storyRepository.getCompletedStories(pageRequest(page, size));
    }

    public Page<Story> getOnGoingStories(
            TelegramUser user,
            int page,
            int size) {

        // Public catalog: every user can browse every active ongoing story.
        // Access is checked only when episodes are requested.
        return storyRepository.getOnGoingStories(pageRequest(page, size));
    }

    /**
     * Stories the actor is allowed to assign to another user.
     * OWNER -> every active story.
     * ADMIN -> only OWNER-mapped stories assigned to that ADMIN.
     */
    public Page<Story> getAssignableStories(
            TelegramUser actor,
            int page,
            int size) {

        Pageable pageable = pageRequest(page, size);

        if (isOwner(actor)) {
            return storyRepository.findByActiveTrue(pageable);
        }

        if (actor != null && actor.getRole() == UserRole.ADMIN) {
            return storyRepository.getActiveStoriesForAdmin(
                    actor,
                    UserRole.OWNER,
                    pageable);
        }

        return Page.empty(pageable);
    }

    /**
     * Stories that may be managed by the actor in story-specific admin tools.
     * OWNER can manage all stories including inactive stories.
     * ADMIN can manage only active OWNER-assigned stories.
     */
    public Page<Story> getManageableStories(
            TelegramUser actor,
            int page,
            int size) {

        Pageable pageable = pageRequest(page, size);

        if (isOwner(actor)) {
            return storyRepository.findAll(pageable);
        }

        if (actor != null && actor.getRole() == UserRole.ADMIN) {
            return storyRepository.getActiveStoriesForAdmin(
                    actor,
                    UserRole.OWNER,
                    pageable);
        }

        return Page.empty(pageable);
    }

    public List<Story> getAccessibleStories(TelegramUser user) {

        if (isOwner(user)) {
            return storyRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        }

        if (user != null && user.getRole() == UserRole.ADMIN) {
            return storyRepository.getActiveStoriesForAdminList(user, UserRole.OWNER);
        }

        if (user == null) {
            return List.of();
        }

        return storyRepository.getActiveStoriesForUserList(user);
    }

    // =========================================
    // GRANT / REVOKE
    // =========================================

    @Transactional
    public UserStoryAccess grantStoryAccess(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        validateGrant(actor, targetUser, story);

        UserStoryAccess existing = userStoryAccessRepository
                .findByTelegramUserAndStory(targetUser, story)
                .orElse(null);

        // Keep a valid active grant unchanged. However, if the target is now
        // ADMIN and the old row was granted by ADMIN while the target was a
        // USER, hasAssignedStoryAccess() is false. In that case OWNER must be
        // able to upgrade the existing row into a valid OWNER grant.
        if (existing != null
                && Boolean.TRUE.equals(existing.getActive())
                && hasAssignedStoryAccess(targetUser, story)) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();

        UserStoryAccess access = existing == null
                ? UserStoryAccess.builder()
                        .telegramUser(targetUser)
                        .story(story)
                        .build()
                : existing;

        access.setGrantedBy(actor);
        access.setGrantedByRole(actor.getRole());
        access.setActive(true);
        access.setGrantedAt(now);
        access.setRevokedAt(null);
        access.setUpdatedAt(now);

        UserStoryAccess saved = userStoryAccessRepository.save(access);

        log.info(
                "Story access granted actor={} actorRole={} target={} targetRole={} storyId={}",
                actor.getTelegramId(),
                actor.getRole(),
                targetUser.getTelegramId(),
                targetUser.getRole(),
                story.getId());

        return saved;
    }

    @Transactional
    public void revokeStoryAccess(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        validateRevoke(actor, targetUser, story);

        UserStoryAccess access = userStoryAccessRepository
                .findByTelegramUserAndStory(targetUser, story)
                .orElse(null);

        if (access == null || !Boolean.TRUE.equals(access.getActive())) {
            return;
        }

        access.setActive(false);
        access.setRevokedAt(LocalDateTime.now());
        access.setUpdatedAt(LocalDateTime.now());

        userStoryAccessRepository.save(access);

        log.info(
                "Story access revoked actor={} actorRole={} target={} targetRole={} storyId={}",
                actor.getTelegramId(),
                actor.getRole(),
                targetUser.getTelegramId(),
                targetUser.getRole(),
                story.getId());
    }

    @Transactional
    public boolean toggleStoryAccess(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        boolean currentlyAssigned = hasAssignedStoryAccess(targetUser, story);

        if (currentlyAssigned) {
            revokeStoryAccess(actor, targetUser, story);
            return false;
        }

        grantStoryAccess(actor, targetUser, story);
        return true;
    }

    /**
     * OWNER-only bulk action used by the Telegram story-access screen.
     * Grants every currently active story to the selected ADMIN/USER.
     *
     * This is intentionally not available to ADMIN because ADMIN story
     * assignment must stay limited to stories explicitly granted by OWNER.
     */
    @Transactional
    public int grantAllActiveStories(
            TelegramUser actor,
            TelegramUser targetUser) {

        if (!isOwner(actor)) {
            throw new SecurityException("Only OWNER can grant all stories");
        }

        if (targetUser == null) {
            throw new IllegalArgumentException("Target user is required");
        }

        if (targetUser.getRole() == UserRole.OWNER) {
            throw new IllegalArgumentException("OWNER does not require story mapping");
        }

        List<Story> activeStories = storyRepository.findByActiveTrueOrderByIdDesc();
        int newlyGranted = 0;

        for (Story story : activeStories) {
            boolean alreadyAssigned = hasAssignedStoryAccess(targetUser, story);

            grantStoryAccess(actor, targetUser, story);

            if (!alreadyAssigned) {
                newlyGranted++;
            }
        }

        log.info(
                "All active story access granted actor={} target={} targetRole={} totalActive={} newlyGranted={}",
                actor.getTelegramId(),
                targetUser.getTelegramId(),
                targetUser.getRole(),
                activeStories.size(),
                newlyGranted);

        return newlyGranted;
    }

    // =========================================
    // PERMISSION VALIDATION
    // =========================================

    private void validateGrant(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        validateCommon(actor, targetUser, story);

        if (actor.getRole() == UserRole.OWNER) {

            if (targetUser.getRole() == UserRole.OWNER) {
                throw new IllegalArgumentException("OWNER does not require story mapping");
            }

            return;
        }

        if (actor.getRole() == UserRole.ADMIN) {

            if (targetUser.getRole() != UserRole.USER) {
                throw new SecurityException("ADMIN can assign stories only to USER accounts");
            }

            if (!hasStoryAccess(actor, story)) {
                throw new SecurityException("ADMIN cannot assign a story that is not assigned to the ADMIN");
            }

            return;
        }

        throw new SecurityException("USER cannot assign story access");
    }

    private void validateRevoke(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        // Same authority model is used for revoke as grant.
        validateGrant(actor, targetUser, story);
    }

    private void validateCommon(
            TelegramUser actor,
            TelegramUser targetUser,
            Story story) {

        if (actor == null) {
            throw new IllegalArgumentException("Actor is required");
        }

        if (targetUser == null) {
            throw new IllegalArgumentException("Target user is required");
        }

        if (story == null) {
            throw new IllegalArgumentException("Story is required");
        }

        if (actor.getRole() == null) {
            throw new SecurityException("Actor role is required");
        }
    }

    private boolean isOwner(TelegramUser user) {
        return user != null && user.getRole() == UserRole.OWNER;
    }

    private Pageable pageRequest(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 1);
        return PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Direction.DESC, "id"));
    }
}
