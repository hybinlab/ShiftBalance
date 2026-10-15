package com.shiftbalance.personnel;

import com.shiftbalance.personnel.PersonnelDtos.CreatePersonRequest;
import com.shiftbalance.personnel.PersonnelDtos.GroupView;
import com.shiftbalance.personnel.PersonnelDtos.PersonView;
import com.shiftbalance.personnel.PersonnelDtos.PositionView;
import com.shiftbalance.personnel.PersonnelDtos.UpdatePersonRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class PersonnelController {
    private final PersonnelService personnelService;

    public PersonnelController(PersonnelService personnelService) {
        this.personnelService = personnelService;
    }

    @GetMapping("/groups")
    public List<GroupView> groups() {
        return personnelService.groups();
    }

    @GetMapping("/positions")
    public List<PositionView> positions() {
        return personnelService.positions();
    }

    @GetMapping("/people")
    public List<PersonView> people(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return personnelService.people(asOf);
    }

    @PostMapping("/people")
    @ResponseStatus(HttpStatus.CREATED)
    public PersonView createPerson(@Valid @RequestBody CreatePersonRequest request) {
        return personnelService.createPerson(request);
    }

    @PutMapping("/people/{id}")
    public PersonView updatePerson(
            @PathVariable long id,
            @Valid @RequestBody UpdatePersonRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return personnelService.updatePerson(id, request, asOf);
    }
}
