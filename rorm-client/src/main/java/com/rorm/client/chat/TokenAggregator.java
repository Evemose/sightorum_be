package com.rorm.client.chat;

import com.rorm.ai.chat.StreamToken;
import com.rorm.ai.swarm.EventId;
import com.rorm.ai.swarm.SwarmStreamEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Coalesces consecutive {@link SwarmStreamEvent.AgentToken} events of the same
 * agent and same token kind (Text or Thinking) into a single concatenated
 * event, bounded by a max byte size so a long burst can't produce one huge
 * payload. All other event variants and non-text token kinds pass through
 * unchanged. Order is preserved.
 */
public final class TokenAggregator {

    private TokenAggregator() {
    }

    public static List<SwarmStreamEvent> aggregate(List<SwarmStreamEvent> input, int maxBytes) {
        var out = new ArrayList<SwarmStreamEvent>(input.size());
        StringBuilder buf = null;
        Kind bufKind = null;
        EventId bufEventId = null;
        int bufSequence = 0;

        for (var event : input) {
            var fragment = textFragment(event);
            if (fragment == null) {
                if (buf != null) {
                    out.add(flush(bufKind, bufEventId, bufSequence, buf));
                    buf = null;
                }
                out.add(event);
                continue;
            }

            if (buf != null
                && fragment.kind() == bufKind
                && fragment.eventId().equals(bufEventId)
                && buf.length() + fragment.text().length() <= maxBytes) {
                buf.append(fragment.text());
                continue;
            }

            if (buf != null) {
                out.add(flush(bufKind, bufEventId, bufSequence, buf));
            }
            buf = new StringBuilder(fragment.text());
            bufKind = fragment.kind();
            bufEventId = fragment.eventId();
            bufSequence = fragment.sequence();
        }

        if (buf != null) {
            out.add(flush(bufKind, bufEventId, bufSequence, buf));
        }
        return out;
    }

    private static Fragment textFragment(SwarmStreamEvent event) {
        if (!(event instanceof SwarmStreamEvent.AgentToken(EventId eventId, int sequence, StreamToken token1))) {
            return null;
        }
        return switch (token1) {
            case StreamToken.Text t -> new Fragment(eventId, sequence, Kind.TEXT, t.content());
            case StreamToken.Thinking t -> new Fragment(eventId, sequence, Kind.THINKING, t.content());
            case StreamToken.ToolCall _,
                 StreamToken.ServerTool _,
                 StreamToken.SearchResult _,
                 StreamToken.Citation _ -> null;
        };
    }

    private static SwarmStreamEvent.AgentToken flush(Kind kind, EventId eventId, int sequence,
                                                     StringBuilder buf) {
        var text = buf.toString();
        StreamToken token = kind == Kind.THINKING
            ? new StreamToken.Thinking(text)
            : new StreamToken.Text(text);
        return new SwarmStreamEvent.AgentToken(eventId, sequence, token);
    }

    private enum Kind {TEXT, THINKING}

    private record Fragment(EventId eventId, int sequence, Kind kind, String text) {}
}
