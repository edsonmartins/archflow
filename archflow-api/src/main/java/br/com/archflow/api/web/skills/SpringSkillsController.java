package br.com.archflow.api.web.skills;

import br.com.archflow.api.skills.SkillsController;
import br.com.archflow.api.skills.dto.SkillDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP binding for {@link SkillsController}. Lives under
 * {@code /api/admin/skills} because the skills adapter is treated as an
 * admin-level resource (toggles affect all agents on the instance).
 */
@RestController
@RequestMapping("/api/admin/skills")
public class SpringSkillsController {

    private final SkillsController delegate;

    public SpringSkillsController(SkillsController delegate) {
        this.delegate = delegate;
    }

    @GetMapping
    public List<SkillDto> list() {
        return delegate.listSkills();
    }

    @GetMapping("/active")
    public List<SkillDto> active() {
        return delegate.listActiveSkills();
    }

    @PostMapping("/{name}/activate")
    public SkillDto activate(@PathVariable String name) {
        return delegate.activate(name);
    }

    @DeleteMapping("/{name}/activate")
    public void deactivate(@PathVariable String name) {
        delegate.deactivate(name);
    }

    @GetMapping("/{skillName}/resources/{resourceName}")
    public ResponseEntity<SkillDto.SkillResourceDto> resource(
            @PathVariable String skillName,
            @PathVariable String resourceName) {
        return delegate.getResource(skillName, resourceName)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
