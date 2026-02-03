package com.rorm.client.metamodel;

import com.rorm.client.metamodel.dto.ModelSpaceResponse;
import com.rorm.client.validation.ValidSchemaName;
import com.rorm.dto.MetamodelDTO.RootDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/metamodels")
@RequiredArgsConstructor
@Validated
public class MetamodelController {

    private final MetamodelService metamodelService;

    @GetMapping
    public ResponseEntity<List<ModelSpaceResponse>> listMetamodels() {
        return ResponseEntity.ok(metamodelService.listMetamodels());
    }

    @GetMapping("/{schema}")
    public ResponseEntity<ModelSpaceResponse> getMetamodel(
        @PathVariable @ValidSchemaName String schema
    ) {
        return ResponseEntity.ok(metamodelService.getMetamodel(schema));
    }

    @GetMapping("/{schema}/roots/{name}")
    public ResponseEntity<RootDTO> getRoot(
        @PathVariable @ValidSchemaName String schema,
        @PathVariable @NotBlank(message = "{validation.metamodel.rootName}") String name
    ) {
        return ResponseEntity.ok(metamodelService.getRoot(schema, name));
    }
}
