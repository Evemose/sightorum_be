package com.rorm.client.research;

import com.rorm.client.metamodel.Metamodel;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "researches")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Research {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String swarmId;

    @NotNull
    @ManyToOne(optional = false)
    @JoinColumn(name = "metamodel_id", nullable = false)
    private Metamodel metamodel;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResearchStatus status;

    @NotNull
    @OneToMany(mappedBy = "research", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ResearchNode> nodes = new ArrayList<>();

    @NotNull
    @CreatedDate
    private Instant createdAt;

    @NotNull
    @LastModifiedDate
    private Instant updatedAt;

    public Research(String swarmId, Metamodel metamodel) {
        this.swarmId = swarmId;
        this.metamodel = metamodel;
        this.status = ResearchStatus.PENDING;
    }
}
