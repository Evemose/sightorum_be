package com.rorm.client.metamodel;

import com.rorm.client.metamodel.dto.ModelSpaceResponse;
import com.rorm.dto.MetamodelDTO.RootDTO;
import com.rorm.mapper.MetamodelMapper;
import com.rorm.metamodel.ModelSpace;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MetamodelService {

    private final MetamodelRepository repository;
    private final MetamodelMapper metamodelMapper;

    @Transactional(readOnly = true)
    public List<ModelSpaceResponse> listMetamodels() {
        return repository.findAll().stream()
            .map(this::toResponse)
            .toList();
    }

    private ModelSpaceResponse toResponse(Metamodel metamodel) {
        return new ModelSpaceResponse(
            metamodel.getId(),
            metamodel.getSchemaName(),
            metamodelMapper.toDTO(metamodel.getModelSpace()),
            metamodel.getCreatedAt(),
            metamodel.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public ModelSpaceResponse getMetamodel(String schemaName) {
        var metamodel = repository.findBySchemaName(schemaName)
            .orElseThrow(() -> new EntityNotFoundException("Metamodel not found: " + schemaName));
        return toResponse(metamodel);
    }

    @Transactional(readOnly = true)
    public RootDTO getRoot(String schemaName, String rootName) {
        var modelSpace = getModelSpace(schemaName);
        var root = modelSpace.roots().stream()
            .filter(r -> r.primaryTableName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new EntityNotFoundException("Root not found: " + rootName));
        return metamodelMapper.toDTO(root);
    }

    @Transactional(readOnly = true)
    public ModelSpace getModelSpace(String schemaName) {
        var metamodel = repository.findBySchemaName(schemaName)
            .orElseThrow(() -> new EntityNotFoundException("Metamodel not found: " + schemaName));
        return metamodel.getModelSpace();
    }

    @Transactional
    public void saveMetamodel(String schemaName, ModelSpace modelSpace) {
        var existing = repository.findBySchemaName(schemaName);
        if (existing.isPresent()) {
            existing.get().setModelSpace(modelSpace);
            repository.save(existing.get());
        } else {
            repository.save(new Metamodel(schemaName, modelSpace));
        }
    }
}
