package com.shiftbalance.rotation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rotation")
public class RotationController {
    @PostMapping("/next")
    public DutyRotation.RotationResult next(@Valid @RequestBody RotationRequest request) {
        return DutyRotation.next(
                new DutyRotation.RotationState(request.firstEarlyDownGroup(), request.workDayIndex()),
                request.groupACode(),
                request.groupBCode());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidRotation(IllegalArgumentException exception) {
        return new ErrorResponse(exception.getMessage());
    }

    public record RotationRequest(
            @NotBlank String groupACode,
            @NotBlank String groupBCode,
            @NotBlank String firstEarlyDownGroup,
            @Min(0) @Max(1) int workDayIndex) {}

    public record ErrorResponse(String message) {}
}
