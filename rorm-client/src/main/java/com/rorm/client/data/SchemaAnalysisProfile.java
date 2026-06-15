package com.rorm.client.data;

import com.rorm.client.metamodel.Metamodel;
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
@Table(name = "schema_profiles")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SchemaAnalysisProfile {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "schema_name",
        referencedColumnName = "schema_name",
        nullable = false,
        unique = true,
        foreignKey = @ForeignKey(name = "fk_schema_profiles_metamodel")
    )
    private Metamodel metamodel;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private SchemaProfile profile;

    @CreatedDate
    private Instant createdAt;

    public SchemaAnalysisProfile(Metamodel metamodel, SchemaProfile profile) {
        this.metamodel = metamodel;
        this.profile = profile;
    }

    public String getSchemaName() {
        return metamodel.getSchemaName();
    }
}
