package com.rorm.client.data;

import com.rorm.dataimport.pipeline.profile.SchemaProfile;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "schema_profiles", indexes = {
    @Index(name = "idx_schema_profiles_schema_name", columnList = "schemaName", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SchemaAnalysisProfile {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String schemaName;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private SchemaProfile profile;

    @CreatedDate
    private Instant createdAt;

    public SchemaAnalysisProfile(String schemaName, SchemaProfile profile) {
        this.schemaName = schemaName;
        this.profile = profile;
    }
}
