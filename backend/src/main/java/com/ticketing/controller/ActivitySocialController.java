package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.common.PageResponse;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.service.ActivityPopularService;
import com.ticketing.service.ActivitySocialService;
import com.ticketing.vo.ActivityEngagementView;
import com.ticketing.vo.FollowedActivityView;
import com.ticketing.vo.PopularActivityView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ActivitySocialController {
    private final ActivitySocialService social;
    private final ActivityPopularService popular;

    public ActivitySocialController(ActivitySocialService social, ActivityPopularService popular) {
        this.social = social;
        this.popular = popular;
    }

    @GetMapping("/activities/popular")
    public ApiResponse<List<PopularActivityView>> popular(
            @RequestParam(defaultValue = "10") @Min(1) @Max(10) int limit) {
        return ApiResponse.success(popular.popular(limit));
    }

    @GetMapping("/activities/{activityId}/engagement")
    public ApiResponse<ActivityEngagementView> engagement(@PathVariable @Positive long activityId,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.success(social.engagement(activityId, principal == null ? null : principal.getId()));
    }

    @PutMapping("/activities/{activityId}/like")
    public ApiResponse<ActivityEngagementView> like(@PathVariable @Positive long activityId,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.success(social.like(principal.getId(), activityId));
    }

    @DeleteMapping("/activities/{activityId}/like")
    public ApiResponse<ActivityEngagementView> unlike(@PathVariable @Positive long activityId,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.success(social.unlike(principal.getId(), activityId));
    }

    @PutMapping("/activities/{activityId}/follow")
    public ApiResponse<ActivityEngagementView> follow(@PathVariable @Positive long activityId,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.success(social.follow(principal.getId(), activityId));
    }

    @DeleteMapping("/activities/{activityId}/follow")
    public ApiResponse<ActivityEngagementView> unfollow(@PathVariable @Positive long activityId,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.success(social.unfollow(principal.getId(), activityId));
    }

    @GetMapping("/me/followed-activities")
    public ApiResponse<PageResponse<FollowedActivityView>> followed(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(20) int size) {
        return ApiResponse.success(social.followed(principal.getId(), page, size));
    }
}
