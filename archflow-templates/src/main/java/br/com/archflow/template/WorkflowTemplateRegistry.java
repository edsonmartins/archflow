package br.com.archflow.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for workflow templates.
 *
 * <p>The registry maintains a catalog of available workflow templates
 * and provides methods for discovering and instantiating them.
 *
 * <p>Templates are discovered via ServiceLoader (SPI) or manually registered.
 */
public class WorkflowTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTemplateRegistry.class);

    private static volatile WorkflowTemplateRegistry instance;

    private final Map<String, WorkflowTemplate> templates;
    private final Map<String, Set<String>> templatesByCategory;

    private WorkflowTemplateRegistry() {
        this.templates = new ConcurrentHashMap<>();
        this.templatesByCategory = new ConcurrentHashMap<>();
        loadTemplates();
    }

    /**
     * Gets the singleton instance of the registry.
     */
    public static WorkflowTemplateRegistry getInstance() {
        if (instance == null) {
            synchronized (WorkflowTemplateRegistry.class) {
                if (instance == null) {
                    instance = new WorkflowTemplateRegistry();
                }
            }
        }
        return instance;
    }

    /**
     * Resets the registry (mainly for testing).
     */
    public static void reset() {
        synchronized (WorkflowTemplateRegistry.class) {
            if (instance != null) {
                instance.templates.clear();
                instance.templatesByCategory.clear();
            }
            instance = null;
        }
    }

    /**
     * Loads templates via ServiceLoader (SPI).
     */
    private void loadTemplates() {
        try {
            ServiceLoader<WorkflowTemplate> loader = ServiceLoader.load(WorkflowTemplate.class);
            for (WorkflowTemplate template : loader) {
                register(template);
            }
        } catch (Exception e) {
            log.warn("Failed to load templates via SPI: {}", e.getMessage());
        }
    }

    /**
     * Registers a template.
     *
     * @param template The template to register
     */
    public void register(WorkflowTemplate template) {
        templates.put(template.getId(), template);

        templatesByCategory
                .computeIfAbsent(template.getCategory(), k -> new HashSet<>())
                .add(template.getId());

        log.info("Registered template: {} ({})", template.getId(), template.getDisplayName());
    }

    /**
     * Gets a template by ID.
     *
     * @param id The template ID
     * @return The template, or empty if not found
     */
    public Optional<WorkflowTemplate> getTemplate(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    /**
     * Gets all registered templates.
     *
     * @return All templates
     */
    public Collection<WorkflowTemplate> getAllTemplates() {
        return Collections.unmodifiableCollection(templates.values());
    }

    /**
     * Gets templates by category.
     *
     * @param category The category
     * @return Templates in the category
     */
    public List<WorkflowTemplate> getTemplatesByCategory(String category) {
        Set<String> templateIds = templatesByCategory.getOrDefault(category, Set.of());
        return templateIds.stream()
                .map(templates::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Searches templates by tag.
     *
     * @param tag The tag to search for
     * @return Templates with the given tag
     */
    public List<WorkflowTemplate> searchByTag(String tag) {
        return templates.values().stream()
                .filter(t -> t.getTags().contains(tag))
                .toList();
    }

    /**
     * Searches templates by keyword in name or description.
     *
     * @param keyword The keyword to search for
     * @return Matching templates
     */
    public List<WorkflowTemplate> search(String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        return templates.values().stream()
                .filter(t -> t.getDisplayName().toLowerCase().contains(lowerKeyword)
                        || t.getDescription().toLowerCase().contains(lowerKeyword))
                .toList();
    }

    /**
     * Gets all available categories.
     *
     * @return Set of category names
     */
    public Set<String> getCategories() {
        return Set.copyOf(templatesByCategory.keySet());
    }

    /**
     * Checks if a template exists.
     *
     * @param id The template ID
     * @return true if the template exists
     */
    public boolean hasTemplate(String id) {
        return templates.containsKey(id);
    }

    /**
     * Removes a template.
     *
     * @param id The template ID
     * @return The removed template, or null if not found
     */
    public WorkflowTemplate unregister(String id) {
        WorkflowTemplate removed = templates.remove(id);
        if (removed != null) {
            templatesByCategory.get(removed.getCategory()).remove(id);
            log.info("Unregistered template: {}", id);
        }
        return removed;
    }

    /**
     * Gets the count of registered templates.
     *
     * @return Number of templates
     */
    public int size() {
        return templates.size();
    }
}
