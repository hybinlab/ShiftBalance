package com.shiftbalance.statistics;

import com.shiftbalance.statistics.StatisticsDtos.PersonPositionAssignmentsResponse;
import com.shiftbalance.statistics.StatisticsDtos.PositionDistributionResponse;
import com.shiftbalance.statistics.StatisticsDtos.ScheduleStatistics;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1")
public class StatisticsController {
    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/statistics/person-position-assignments")
    public PersonPositionAssignmentsResponse personPositionAssignments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long personId,
            @RequestParam(required = false) Long positionId,
            @RequestParam(required = false) Long groupId) {
        return statisticsService.personPositionAssignments(startDate, endDate, personId, positionId, groupId);
    }

    @GetMapping("/statistics/position-distribution")
    public PositionDistributionResponse positionDistribution(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long positionId,
            @RequestParam(required = false) Long groupId) {
        return statisticsService.positionDistribution(startDate, endDate, positionId, groupId);
    }

    @GetMapping("/schedules/{scheduleId}/statistics")
    public ScheduleStatistics scheduleStatistics(@PathVariable long scheduleId) {
        return statisticsService.scheduleStatistics(scheduleId);
    }
}
