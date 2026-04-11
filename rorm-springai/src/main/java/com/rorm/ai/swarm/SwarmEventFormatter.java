package com.rorm.ai.swarm;

import com.rorm.ai.chat.StreamToken;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dual-mode formatter:
 * <ul>
 *   <li><b>Live mode</b> (exactly one active agent): tokens write to stdout as
 *       they arrive, no buffering, no role captions. The single agent's
 *       output streams like a normal chat.</li>
 *   <li><b>Buffered mode</b> (two or more active agents): each agent's tokens
 *       accumulate per mode and flush as complete blocks on mode change, with
 *       agent role captions inserted only when the active emitter changes.
 *       This prevents unreadable per-token interleaving.</li>
 * </ul>
 * Mode switches on agent start/end: the first agent of a concurrent group
 * terminates its live stream and starts buffering; when a concurrent group
 * collapses back to one agent, the remaining agent flushes its pending
 * buffered block and resumes live streaming.
 */
public class SwarmEventFormatter implements AutoCloseable {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String YELLOW = "\033[33m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String MAGENTA = "\033[35m";
    private static final String BLUE = "\033[34m";

    private final PrintStream out;
    private final Sinks.Many<SwarmEvent> events;
    private final Object lock = new Object();
    private final Map<UUID, AgentState> active = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Disposable> subscriptions = new CopyOnWriteArrayList<>();
    private UUID lastBufferedEmitter;

    public SwarmEventFormatter() {
        this(Sinks.many().replay().all());
    }

    public SwarmEventFormatter(Sinks.Many<SwarmEvent> events) {
        this(events, System.out);
    }

    public SwarmEventFormatter(Sinks.Many<SwarmEvent> events, PrintStream out) {
        this.events = events;
        this.out = out;
        subscriptions.add(events.asFlux().subscribe(this::onEvent, this::onError));
    }

    private static boolean endsWithSentenceBoundary(CharSequence cs) {
        var len = cs.length();
        if (len < 2) {
            return false;
        }
        var last = cs.charAt(len - 1);
        if (last == '\n') {
            return true;
        }
        if (last != ' ') {
            return false;
        }
        var prev = cs.charAt(len - 2);
        return prev == '.' || prev == '?' || prev == '!';
    }

    private static String tagString(Map<String, String> tags) {
        if (tags.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder(" {");
        var first = true;
        for (var e : tags.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    private static String parentString(EventId id) {
        if (id.parents().isEmpty()) {
            return "";
        }
        var sb = new StringBuilder(" ← ");
        for (var i = 0; i < id.parents().size(); i++) {
            var p = id.parents().get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(p.kind()).append('(').append(p.shortToken()).append(')');
        }
        return sb.toString();
    }

    public Sinks.Many<SwarmEvent> events() {
        return events;
    }

    @Override
    public void close() {
        synchronized (lock) {
            for (var state : active.values()) {
                flushBlock(state);
                printFooter(state);
            }
            active.clear();
            lastBufferedEmitter = null;
            out.flush();
        }
        subscriptions.forEach(Disposable::dispose);
        subscriptions.clear();
    }

    private void flushBlock(AgentState state) {
        if (state.pendingMode == Mode.NONE || state.pendingContent.length() == 0) {
            state.pendingMode = Mode.NONE;
            state.pendingContent.setLength(0);
            return;
        }
        var token = state.id.token();
        if (active.size() > 1 && !token.equals(lastBufferedEmitter)) {
            out.println(DIM + CYAN + "[" + state.id.kind() + ":" + state.id.shortToken() + "]" + RESET);
            lastBufferedEmitter = token;
        }
        if (state.lastPrintedMode != state.pendingMode) {
            var label = modeLabel(state.pendingMode);
            if (label != null) {
                out.println("  " + label);
            }
            state.lastPrintedMode = state.pendingMode;
        }
        var color = colorFor(state.pendingMode);
        var content = state.pendingContent.toString().strip();
        if (!content.isEmpty()) {
            for (var line : content.split("\n", -1)) {
                out.println("  " + color + line + RESET);
            }
        }
        out.flush();
        state.pendingMode = Mode.NONE;
        state.pendingContent.setLength(0);
    }

    private void printFooter(AgentState state) {
        var id = state.id;
        out.println(BOLD + GREEN + "└── " + id.kind() + " "
                    + DIM + "(" + id.shortToken() + ")" + RESET
                    + BOLD + GREEN + " done" + RESET);
        out.flush();
        lastBufferedEmitter = null;
    }

    private static String modeLabel(Mode mode) {
        return switch (mode) {
            case THINKING -> BOLD + DIM + MAGENTA + "[thinking]" + RESET;
            case TOOL -> BOLD + YELLOW + "[tool]" + RESET;
            case SERVER -> BOLD + BLUE + "[server tool]" + RESET;
            case SEARCH -> BOLD + CYAN + "[search result]" + RESET;
            case TEXT, NONE -> null;
        };
    }

    private static String colorFor(Mode mode) {
        return switch (mode) {
            case THINKING -> DIM + MAGENTA;
            case TOOL -> YELLOW;
            case SERVER -> BLUE;
            case SEARCH -> CYAN;
            case TEXT, NONE -> "";
        };
    }

    private void onEvent(SwarmEvent event) {
        switch (event) {
            case SwarmEvent.StartEvent start -> onStart(start);
            case SwarmEvent.EndEvent<?> end -> onEnd(end);
        }
    }

    private void onStart(SwarmEvent.StartEvent start) {
        var state = new AgentState(start.id());
        synchronized (lock) {
            active.put(start.id().token(), state);
            printHeader(state);
        }
        var sub = start.tokenStream().subscribe(
            token -> onToken(state, token),
            this::onError
        );
        subscriptions.add(sub);
    }

    private void onEnd(SwarmEvent.EndEvent<?> end) {
        synchronized (lock) {
            var state = active.remove(end.id().token());
            if (state == null) {
                return;
            }
            flushBlock(state);
            printFooter(state);
        }
    }

    private void onToken(AgentState state, StreamToken token) {
        synchronized (lock) {
            var mode = modeOf(token);
            if (state.pendingMode != Mode.NONE && state.pendingMode != mode) {
                flushBlock(state);
            }
            state.pendingMode = mode;
            state.pendingContent.append(textOf(token));
            if (active.size() == 1 && endsWithSentenceBoundary(state.pendingContent)) {
                flushBlock(state);
                state.pendingMode = mode;
            }
        }
    }

    private String textOf(StreamToken token) {
        return switch (token) {
            case StreamToken.Text t -> t.content();
            case StreamToken.Thinking t -> t.content();
            case StreamToken.ToolCall tc -> tc.name();
            case StreamToken.ServerTool st -> st.query() != null && !st.query().isEmpty()
                ? st.name() + ": " + st.query()
                : st.name();
            case StreamToken.SearchResult sr -> sr.content();
            case StreamToken.Citation c -> {
                var label = c.title() != null && !c.title().isEmpty() ? c.title() : c.url();
                yield " [ref: " + label + "]";
            }
        };
    }

    private Mode modeOf(StreamToken token) {
        return switch (token) {
            case StreamToken.Text _ -> Mode.TEXT;
            case StreamToken.Thinking _ -> Mode.THINKING;
            case StreamToken.ToolCall _ -> Mode.TOOL;
            case StreamToken.ServerTool _ -> Mode.SERVER;
            case StreamToken.SearchResult _ -> Mode.SEARCH;
            case StreamToken.Citation _ -> Mode.TEXT;
        };
    }

    private void printHeader(AgentState state) {
        var id = state.id;
        out.println();
        out.println(BOLD + CYAN + "┌── " + id.kind() + " "
                    + DIM + "(" + id.shortToken() + ")" + tagString(id.tags())
                    + parentString(id) + RESET);
        out.flush();
        lastBufferedEmitter = null;
    }

    private void onError(Throwable err) {
        synchronized (lock) {
            var msg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
            out.println(BOLD + RED + "[swarm error] " + msg + RESET);
            out.flush();
        }
    }

    private enum Mode {
        NONE, TEXT, THINKING, TOOL, SERVER, SEARCH
    }

    private static final class AgentState {
        final EventId id;
        final StringBuilder pendingContent = new StringBuilder();
        Mode pendingMode = Mode.NONE;
        Mode lastPrintedMode = Mode.NONE;

        AgentState(EventId id) {
            this.id = id;
        }
    }
}
