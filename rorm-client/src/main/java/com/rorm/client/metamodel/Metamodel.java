package com.rorm.client.metamodel;

import com.rorm.metamodel.ModelSpace;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "metamodels")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Metamodel {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "schema_name", nullable = false, unique = true)
    private String schemaName;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private ModelSpace modelSpace;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public Metamodel(String schemaName, ModelSpace modelSpace) {
        this.schemaName = schemaName;
        this.modelSpace = modelSpace;
    }
}
