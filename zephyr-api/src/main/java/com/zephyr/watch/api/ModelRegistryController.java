package com.zephyr.watch.api;

import com.zephyr.watch.api.service.ModelRegistryService;
import com.zephyr.watch.common.dto.ModelRegistryRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class ModelRegistryController {

    private final ModelRegistryService modelRegistryService;

    public ModelRegistryController(ModelRegistryService modelRegistryService) {
        this.modelRegistryService = modelRegistryService;
    }

    @GetMapping
    public Map<String, Object> models(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok("model-registry", modelRegistryService.models(status, limit));
    }

    @GetMapping("/active")
    public Map<String, Object> activeModel() {
        return ApiResponse.ok("active-model", modelRegistryService.activeModel());
    }

    @PostMapping
    public Map<String, Object> register(@RequestBody ModelRegistryRequest request) {
        return ApiResponse.ok("model-register", modelRegistryService.register(request));
    }

    @PostMapping("/{modelVersion}/activate")
    public Map<String, Object> activate(@PathVariable String modelVersion) {
        return ApiResponse.ok("model-activate", modelRegistryService.activate(modelVersion));
    }
}
