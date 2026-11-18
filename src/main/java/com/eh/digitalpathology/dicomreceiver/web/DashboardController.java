package com.eh.digitalpathology.dicomreceiver.web;

import com.eh.digitalpathology.dicomreceiver.entity.TransferRecord;
import com.eh.digitalpathology.dicomreceiver.service.DatabaseService;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * Single reporting screen: lists every processed transfer, with search by file name or
 * series ID and sorting by any of the displayed columns.
 */
@Controller
public class DashboardController {

    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "fileName", "seriesInstanceUid", "sopInstanceUid", "transactionDateTime");

    private final DatabaseService databaseService;

    public DashboardController(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(name = "q", required = false) String query,
                             @RequestParam(name = "sort", defaultValue = "transactionDateTime") String sortField,
                             @RequestParam(name = "dir", defaultValue = "desc") String sortDir,
                             Model model) {
        String field = SORTABLE_FIELDS.contains(sortField) ? sortField : "transactionDateTime";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;

        List<TransferRecord> records = databaseService.search(query, Sort.by(direction, field));

        model.addAttribute("records", records);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("sort", field);
        model.addAttribute("dir", direction.name().toLowerCase());
        model.addAttribute("nextDir", direction == Sort.Direction.ASC ? "desc" : "asc");
        return "dashboard";
    }

    @PostMapping("/records/{id}/delete")
    public String delete(@PathVariable("id") Long id,
                         @RequestParam(name = "q", required = false) String query,
                         @RequestParam(name = "sort", required = false) String sortField,
                         @RequestParam(name = "dir", required = false) String sortDir,
                         RedirectAttributes redirect) {
        boolean removed = databaseService.deleteById(id);
        redirect.addFlashAttribute("message", removed ? "Record deleted." : "Record not found.");
        if (query != null && !query.isBlank()) redirect.addAttribute("q", query);
        if (sortField != null) redirect.addAttribute("sort", sortField);
        if (sortDir != null) redirect.addAttribute("dir", sortDir);
        return "redirect:/";
    }
}
