package ua.course.rental;

import java.net.URI;
import java.sql.SQLException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/equipment")
public class EquipmentController {
    private final EquipmentRepository equipment;
    public EquipmentController(EquipmentRepository equipment) { this.equipment = equipment; }
    public record EquipmentInput(String code, long dailyRate, int stock) {}

    @PostMapping
    public ResponseEntity<Equipment> create(@RequestBody EquipmentInput input) throws SQLException {
        if (input.code() == null || input.code().isBlank() || input.code().length() > 40)
            throw new IllegalArgumentException("Некоректний код обладнання");
        var saved = equipment.create(new Equipment(0, input.code(), input.dailyRate(), input.stock()));
        return ResponseEntity.created(URI.create("/equipment/" + saved.id())).body(saved);
    }

    @GetMapping("/{id}")
    public Equipment get(@PathVariable long id) throws SQLException {
        var found = equipment.findById(id);
        if (found == null) throw new ResourceNotFoundException("Обладнання не знайдено");
        return found;
    }
}
