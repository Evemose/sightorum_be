package com.rorm.ai.swarm.communication;

import com.rorm.ai.swarm.EventId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InMemorySwarmCommunicationBufferTest {

    private static final String SCHEMA = "cold_chain";
    private static final String RUN_A = "run-a";
    private static final String RUN_B = "run-b";
    private static final UUID UNRELATED_ASKER = UUID.randomUUID();

    private PeerConsultationAgentFactory factory;
    private PeerConsultationAgent consultant;
    private InMemorySwarmCommunicationBuffer buffer;

    @BeforeEach
    void setUp() {
        factory = mock(PeerConsultationAgentFactory.class);
        consultant = mock(PeerConsultationAgent.class);
        when(factory.create(any())).thenReturn(consultant);
        buffer = new InMemorySwarmCommunicationBuffer(factory);
    }

    @Test
    void openThenAppendThenAsk_consultsWithAccumulatedOutput() {
        var eventId = EventId.root("scout", UUID.randomUUID());
        buffer.openExchange(RUN_A, eventId, "your task: scout the schema");
        buffer.appendOutput(RUN_A, eventId, "first chunk ");
        buffer.appendOutput(RUN_A, eventId, "second chunk");

        var stubbed = new PeerResponse(eventId, "scout", "answer", List.of("first chunk second chunk"));
        when(consultant.consult(any(), any())).thenReturn(stubbed);

        var request = roleQuery("advocate", "scout", "what?");
        var responses = buffer.ask(request);

        var captor = ArgumentCaptor.forClass(AgentExchange.class);
        verify(consultant).consult(captor.capture(), eq(request));
        var snapshot = captor.getValue();
        assertThat(snapshot.kind()).isEqualTo("scout");
        assertThat(snapshot.inputContext()).isEqualTo("your task: scout the schema");
        assertThat(snapshot.output()).isEqualTo("first chunk second chunk");
        assertThat(responses).containsExactly(stubbed);
    }

    private static PeerQueryRequest roleQuery(String askerKind, String targetRole, String question) {
        return new PeerQueryRequest(
            RUN_A, SCHEMA, askerKind, UNRELATED_ASKER,
            new PeerQueryTarget.Role(targetRole), question);
    }

    @Test
    void ask_consultsInProgressBeforeClose() {
        var eventId = EventId.root("advocate", UUID.randomUUID());
        buffer.openExchange(RUN_A, eventId, "task");
        buffer.appendOutput(RUN_A, eventId, "I have argued that ");
        when(consultant.consult(any(), any()))
            .thenReturn(new PeerResponse(eventId, "advocate", "in-progress answer", List.of()));

        var responses = buffer.ask(roleQuery("prosecutor", "advocate", "where are you?"));

        var captor = ArgumentCaptor.forClass(AgentExchange.class);
        verify(consultant).consult(captor.capture(), any());
        assertThat(captor.getValue().output()).isEqualTo("I have argued that ");
        assertThat(responses).hasSize(1);
    }

    @Test
    void ask_continuesAfterMoreAppendsSeesGrownOutput() {
        var eventId = EventId.root("advocate", UUID.randomUUID());
        buffer.openExchange(RUN_A, eventId, "task");
        buffer.appendOutput(RUN_A, eventId, "first ");
        when(consultant.consult(any(), any()))
            .thenReturn(new PeerResponse(eventId, "advocate", "a", List.of()));

        buffer.ask(roleQuery("prosecutor", "advocate", "q1"));

        buffer.appendOutput(RUN_A, eventId, "second");
        buffer.ask(roleQuery("prosecutor", "advocate", "q2"));

        var captor = ArgumentCaptor.forClass(AgentExchange.class);
        verify(consultant, times(2)).consult(captor.capture(), any());
        var snapshots = captor.getAllValues();
        assertThat(snapshots).extracting(AgentExchange::output)
            .containsExactly("first ", "first second");
    }

    @Test
    void ask_excludesAskerFromRoleResolution() {
        var askerId = EventId.root("advocate", UUID.randomUUID());
        var rivalId = EventId.root("advocate", UUID.randomUUID());
        buffer.openExchange(RUN_A, askerId, "asker task");
        buffer.appendOutput(RUN_A, askerId, "asker output");
        buffer.openExchange(RUN_A, rivalId, "rival task");
        buffer.appendOutput(RUN_A, rivalId, "rival output");

        when(consultant.consult(any(), any()))
            .thenReturn(new PeerResponse(rivalId, "advocate", "a", List.of()));

        var request = new PeerQueryRequest(
            RUN_A, SCHEMA, "advocate", askerId.token(),
            new PeerQueryTarget.Role("advocate"), "what is your strongest claim?");
        var responses = buffer.ask(request);

        verify(consultant, times(1)).consult(any(), any());
        var captor = ArgumentCaptor.forClass(AgentExchange.class);
        verify(consultant).consult(captor.capture(), any());
        assertThat(captor.getValue().eventId().token()).isEqualTo(rivalId.token());
        assertThat(responses).hasSize(1);
    }

    @Test
    void ask_specificEvent_alsoExcludesAsker() {
        var askerId = EventId.root("advocate", UUID.randomUUID());
        buffer.openExchange(RUN_A, askerId, "task");
        buffer.appendOutput(RUN_A, askerId, "out");

        var request = new PeerQueryRequest(
            RUN_A, SCHEMA, "advocate", askerId.token(),
            new PeerQueryTarget.SpecificEvent(askerId), "anything?");

        assertThat(buffer.ask(request)).isEmpty();
        verify(factory, never()).create(any());
    }

    @Test
    void ask_withNoOpenExchanges_returnsEmpty() {
        var request = roleQuery("advocate", "scout", "anything?");
        assertThat(buffer.ask(request)).isEmpty();
        verify(factory, never()).create(any());
    }

    @Test
    void appendOutput_beforeOpen_throws() {
        var eventId = EventId.root("scout", UUID.randomUUID());
        assertThatThrownBy(() -> buffer.appendOutput(RUN_A, eventId, "text"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openExchange")
            .hasMessageContaining(eventId.token().toString());
    }

    @Test
    void closeExchange_isLenient_andDoesNotEraseOutput() {
        var eventId = EventId.root("scout", UUID.randomUUID());
        buffer.openExchange(RUN_A, eventId, "task");
        buffer.appendOutput(RUN_A, eventId, "first ");
        buffer.closeExchange(RUN_A, eventId);

        when(consultant.consult(any(), any()))
            .thenReturn(new PeerResponse(eventId, "scout", "a", List.of()));

        buffer.ask(roleQuery("advocate", "scout", "q"));

        var captor = ArgumentCaptor.forClass(AgentExchange.class);
        verify(consultant).consult(captor.capture(), any());
        assertThat(captor.getValue().output()).isEqualTo("first ");
    }

    @Test
    void closeExchange_unknownIsNoop() {
        // Should not throw.
        buffer.closeExchange(RUN_A, EventId.root("scout", UUID.randomUUID()));
    }

    @Test
    void askRole_withMultipleRespondents_consultsEach() {
        var firstId = EventId.root("scout", UUID.randomUUID());
        var secondId = EventId.root("scout", UUID.randomUUID());
        buffer.openExchange(RUN_A, firstId, "task A");
        buffer.appendOutput(RUN_A, firstId, "output A");
        buffer.openExchange(RUN_A, secondId, "task B");
        buffer.appendOutput(RUN_A, secondId, "output B");

        when(consultant.consult(any(), any())).thenAnswer(inv -> {
            AgentExchange e = inv.getArgument(0);
            return new PeerResponse(e.eventId(), e.kind(),
                "answer for " + e.output(), List.of(e.output()));
        });

        var responses = buffer.ask(roleQuery("advocate", "scout", "q"));

        verify(consultant, times(2)).consult(any(), any());
        assertThat(responses)
            .extracting(PeerResponse::respondentEventId, PeerResponse::answer)
            .containsExactlyInAnyOrder(
                tuple(firstId, "answer for output A"),
                tuple(secondId, "answer for output B"));
    }

    @Test
    void askRole_filtersOutOtherKinds() {
        var scoutId = EventId.root("scout", UUID.randomUUID());
        var advocateId = EventId.root("advocate", UUID.randomUUID());
        buffer.openExchange(RUN_A, scoutId, "scout task");
        buffer.appendOutput(RUN_A, scoutId, "scout out");
        buffer.openExchange(RUN_A, advocateId, "advocate task");
        buffer.appendOutput(RUN_A, advocateId, "advocate out");

        when(consultant.consult(any(), any()))
            .thenReturn(new PeerResponse(scoutId, "scout", "a", List.of()));

        buffer.ask(roleQuery("advocate", "scout", "q"));

        verify(consultant, times(1)).consult(any(), any());
        var captor = ArgumentCaptor.forClass(AgentExchange.class);
        verify(consultant).consult(captor.capture(), any());
        assertThat(captor.getValue().kind()).isEqualTo("scout");
    }

    @Test
    void askRole_isolatedAcrossRuns() {
        var eventId = EventId.root("scout", UUID.randomUUID());
        buffer.openExchange(RUN_A, eventId, "task");
        buffer.appendOutput(RUN_A, eventId, "out");

        var request = new PeerQueryRequest(
            RUN_B, SCHEMA, "advocate", UNRELATED_ASKER,
            new PeerQueryTarget.Role("scout"), "q");

        assertThat(buffer.ask(request)).isEmpty();
        verify(factory, never()).create(any());
    }

    @Test
    void complete_clearsRunExchanges() {
        var eventId = EventId.root("scout", UUID.randomUUID());
        buffer.openExchange(RUN_A, eventId, "task");
        buffer.appendOutput(RUN_A, eventId, "out");

        buffer.complete(RUN_A);

        assertThat(buffer.ask(roleQuery("advocate", "scout", "q"))).isEmpty();
        verify(factory, never()).create(any());
    }
}
