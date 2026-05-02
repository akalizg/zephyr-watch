package com.zephyr.watch.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class ModelRegistryController {

    @GetMapping
    public Map<String, Object> models() {
        return ApiResponse.ok("model-registry", "Model version management endpoint is ready");
    }
}
