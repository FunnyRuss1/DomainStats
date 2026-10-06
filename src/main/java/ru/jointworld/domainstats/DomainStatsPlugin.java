package ru.jointworld.domainstats;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class DomainStatsPlugin {
    public static final String PERMISSION = "domain.use";
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final SessionTracker sessions = new SessionTracker();
    private final Set<String> observedDomains = new ConcurrentSkipListSet<>();
    private volatile Set<String> configuredDomains = Collections.emptySet();
    private volatile Settings settings;
    private volatile boolean ready;
    private volatile boolean writeFailed;
    private StatsStore store;
    private ExecutorService worker;

    @Inject
    public DomainStatsPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        try {
            reload();
            store = new StatsStore(directory.resolve("domains.db"));
            observedDomains.addAll(store.domains().keySet());
            worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "DomainStats-database");
                thread.setDaemon(true);
                return thread;
            });
            ready = true;
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("domain").plugin(this).build(), new DomainCommand());
            logger.info("DomainStats enabled with permission {}", PERMISSION);
        } catch (Exception error) {
            ready = false;
            if (worker != null) worker.shutdown();
            if (store != null) {
                try { store.close(); } catch (Exception closeError) { error.addSuppressed(closeError); }
            }
            logger.error("DomainStats could not start", error);
        }
    }

    private void reload() throws Exception {
        Settings updated = Settings.load(directory);
        Set<String> domains = new TreeSet<>(updated.domains);
        if (updated.importForcedHosts) {
            for (String host : proxy.getConfiguration().getForcedHosts().keySet()) {
                domains.add(DomainNames.normalize(host));
            }
        }
        configuredDomains = Collections.unmodifiableSet(domains);
        settings = updated;
    }

    @Subscribe
    public void connected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        if (!ready) return;
        // Serialize with disconnect so an ended session cannot be reinserted
        synchronized (sessions) {
            if (!player.isActive() || !sessions.firstConnection(player)) return;
            String domain = domainOf(player);
            String nickname = player.getUsername();
            long now = System.currentTimeMillis();
            try {
                worker.execute(() -> {
                    try {
                        store.record(domain, nickname, now);
                        observedDomains.add(domain);
                    } catch (Exception error) {
                        writeFailed = true;
                        logger.error("Could not save a domain login for {} on {}", nickname, domain, error);
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // Shutdown has already stopped accepting new sessions
            }
        }
    }

    @Subscribe
    public void disconnected(DisconnectEvent event) {
        sessions.disconnected(event.getPlayer());
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) {
        ready = false;
        if (worker == null) return;
        worker.execute(() -> {
            try { store.close(); } catch (Exception error) { logger.error("Could not close domain database", error); }
        });
        worker.shutdown();
        try {
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) logger.error("Domain database shutdown timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static String domainOf(Player player) {
        return DomainNames.fromConnection(player.getVirtualHost().map(address -> address.getHostString()).orElse(null));
    }

    public static boolean allowed(CommandSource source) {
        // Let LuckPerms resolve wildcards and explicit negative permissions normally
        return source instanceof ConsoleCommandSource || source.hasPermission(PERMISSION);
    }

    private Set<String> knownDomains() {
        Set<String> result = new TreeSet<>(configuredDomains);
        result.addAll(observedDomains);
        return result;
    }

    private Map<String, List<OnlinePlayer>> online() {
        Map<String, List<OnlinePlayer>> result = new java.util.HashMap<>();
        for (Player player : proxy.getAllPlayers()) {
            if (!player.isActive() || !player.getCurrentServer().isPresent()) continue;
            String serverName = player.getCurrentServer().map(server -> server.getServerInfo().getName()).orElse("");
            result.computeIfAbsent(domainOf(player), ignored -> new ArrayList<>())
                    .add(new OnlinePlayer(player.getUsername(), serverName));
        }
        result.values().forEach(players -> players.sort(Comparator.comparing(player -> player.nickname.toLowerCase(Locale.ROOT))));
        return result;
    }

    private static void send(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void title(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.GOLD));
    }

    private static void error(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private static Component link(String text, String command) {
        return Component.text(text, NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command)));
    }

    private static int page(String input) {
        try {
            int page = Integer.parseInt(input);
            if (page > 0) return page;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Номер страницы должен быть целым числом от 1");
    }

    private static long pages(long count, int size) { return Math.max(1, (count + size - 1) / size); }

    private static void navigation(CommandSource source, String command, int current, long total) {
        Component row = Component.text("Страница " + current + " из " + total, NamedTextColor.DARK_GRAY);
        if (current > 1) row = row.append(Component.text("  ")).append(link("Назад", command + " " + (current - 1)));
        if (current < total) row = row.append(Component.text("  ")).append(link("Дальше", command + " " + ((long) current + 1)));
        source.sendMessage(row);
    }

    private void overview(CommandSource source, int page) throws Exception {
        Map<String, StatsStore.Totals> stats = store.domains();
        Map<String, List<OnlinePlayer>> online = online();
        Set<String> domains = knownDomains();
        domains.addAll(online.keySet());
        List<String> ordered = new ArrayList<>(domains);
        ordered.sort(Comparator.<String>comparingLong(domain -> stats.getOrDefault(domain, new StatsStore.Totals(0, 0)).joins)
                .reversed().thenComparing(Comparator.naturalOrder()));
        int size = settings.pageSize;
        long totalPages = pages(ordered.size(), size);
        if (page > totalPages) throw new IllegalArgumentException("Такой страницы нет");
        StatsStore.Totals total = store.total();
        title(source, "Домены — " + domains.size());
        send(source, "Из настроек " + configuredDomains.size() + "  С входами " + stats.size());
        send(source, "Уникальных ников " + total.unique + "  Всего входов " + total.joins + "  Онлайн "
                + online.values().stream().mapToInt(List::size).sum());
        int start = (int) (((long) page - 1) * size);
        for (int i = start; i < Math.min((long) start + size, ordered.size()); i++) {
            String domain = ordered.get(i);
            StatsStore.Totals counts = stats.getOrDefault(domain, new StatsStore.Totals(0, 0));
            int active = online.getOrDefault(domain, Collections.emptyList()).size();
            source.sendMessage(link(domain, "/domain " + domain)
                    .append(Component.text("  Уникальных " + counts.unique + "  Входов " + counts.joins + "  Онлайн " + active, NamedTextColor.GRAY)));
        }
        if (ordered.isEmpty()) send(source, "Домен появится после первого входа или добавления в конфиг");
        navigation(source, "/domain list", page, totalPages);
        send(source, "Подробности — /domain домен");
    }

    private void domain(CommandSource source, String domain) throws Exception {
        if (!knownDomains().contains(domain)) throw new IllegalArgumentException("Этот домен пока не встречался и не добавлен в конфиг");
        StatsStore.Totals counts = store.domain(domain);
        List<OnlinePlayer> players = online().getOrDefault(domain, Collections.emptyList());
        title(source, "Домен " + domain);
        send(source, "Уникальных ников — " + counts.unique);
        send(source, "Всего входов — " + counts.joins);
        send(source, "Сейчас онлайн — " + players.size());
        if (!players.isEmpty()) {
            send(source, players.stream().limit(settings.pageSize).map(player -> player.nickname).collect(Collectors.joining(", ")));
        }
        source.sendMessage(link("Кто заходил", "/domain players " + domain).append(Component.text("  "))
                .append(link("Кто онлайн", "/domain online " + domain)));
    }

    private void players(CommandSource source, String domain, int page) throws Exception {
        if (!knownDomains().contains(domain)) throw new IllegalArgumentException("Этот домен пока не встречался и не добавлен в конфиг");
        StatsStore.Totals totals = store.domain(domain);
        int size = settings.pageSize;
        long totalPages = pages(totals.unique, size);
        if (page > totalPages) throw new IllegalArgumentException("Такой страницы нет");
        title(source, "Кто заходил с " + domain);
        send(source, "Уникальных ников " + totals.unique + "  Всего входов " + totals.joins);
        for (StatsStore.Visitor visitor : store.players(domain, size, ((long) page - 1) * size)) {
            send(source, visitor.nickname + " — входов " + visitor.joins);
        }
        if (totals.unique == 0) send(source, "С этого домена ещё никто не заходил");
        navigation(source, "/domain players " + domain, page, totalPages);
    }

    private void online(CommandSource source, String domain, int page) {
        List<OnlinePlayer> players = online().getOrDefault(domain, Collections.emptyList());
        if (!knownDomains().contains(domain) && players.isEmpty()) throw new IllegalArgumentException("Этот домен пока не встречался и не добавлен в конфиг");
        int size = settings.pageSize;
        long totalPages = pages(players.size(), size);
        if (page > totalPages) throw new IllegalArgumentException("Такой страницы нет");
        title(source, "Сейчас онлайн с " + domain + " — " + players.size());
        int start = (int) (((long) page - 1) * size);
        for (int i = start; i < Math.min((long) start + size, players.size()); i++) {
            OnlinePlayer player = players.get(i);
            send(source, player.nickname + " — " + player.server);
        }
        if (players.isEmpty()) send(source, "С этого домена сейчас никто не играет");
        navigation(source, "/domain online " + domain, page, totalPages);
    }

    private static void help(CommandSource source) {
        title(source, "DomainStats");
        send(source, "/domain — все домены и общие счётчики");
        send(source, "/domain list 2 — вторая страница доменов");
        send(source, "/domain домен — статистика одного домена");
        send(source, "/domain players домен — ники и число входов каждого");
        send(source, "/domain players домен 2 — вторая страница ников");
        send(source, "/domain online домен — игроки онлайн и их серверы");
        send(source, "/domain reload — перечитать настройки");
    }

    private final class DomainCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) { return allowed(invocation.source()); }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!allowed(source)) { error(source, "Нет права " + PERMISSION); return; }
            if (!ready) { error(source, "Статистика сейчас недоступна"); return; }
            String[] args = invocation.arguments().clone();
            try {
                worker.execute(() -> {
                    if (!allowed(source)) { error(source, "Нет права " + PERMISSION); return; }
                    try {
                        if (writeFailed) error(source, "Обнаружена ошибка записи статистики — проверь консоль прокси");
                        dispatch(source, args);
                    } catch (IllegalArgumentException error) {
                        error(source, error.getMessage());
                    } catch (Exception error) {
                        logger.error("Domain command failed", error);
                        error(source, "Не удалось прочитать статистику — проверь консоль прокси");
                    }
                });
            } catch (RejectedExecutionException ignored) {
                error(source, "Прокси выключается");
            }
        }

        private void dispatch(CommandSource source, String[] args) throws Exception {
            if (args.length == 0) { overview(source, 1); return; }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "help":
                    help(source);
                    return;
                case "reload":
                    if (args.length != 1) throw new IllegalArgumentException("Используй /domain reload");
                    reload();
                    send(source, "Настройки перечитаны  Домены из настроек " + configuredDomains.size());
                    return;
                case "list":
                    if (args.length > 2) throw new IllegalArgumentException("Используй /domain list или /domain list 2");
                    overview(source, args.length == 2 ? page(args[1]) : 1);
                    return;
                case "players":
                case "online":
                    if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Используй /domain " + sub + " домен или /domain " + sub + " домен 2");
                    String domain = DomainNames.normalize(args[1]);
                    int page = args.length == 3 ? page(args[2]) : 1;
                    if (sub.equals("players")) players(source, domain, page); else online(source, domain, page);
                    return;
                default:
                    if (args.length != 1) throw new IllegalArgumentException("Справка — /domain help");
                    if (sub.matches("[0-9]+")) overview(source, page(sub)); else domain(source, DomainNames.normalize(args[0]));
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (!allowed(invocation.source())) return Collections.emptyList();
            String[] args = invocation.arguments();
            Set<String> choices = new TreeSet<>();
            if (args.length <= 1) {
                choices.addAll(Arrays.asList("list", "players", "online", "reload", "help"));
                choices.addAll(knownDomains());
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("players") || args[0].equalsIgnoreCase("online"))) {
                choices.addAll(knownDomains());
            } else return Collections.emptyList();
            String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            return choices.stream().filter(value -> value.startsWith(prefix)).limit(100).collect(Collectors.toList());
        }
    }

    private static final class OnlinePlayer {
        final String nickname;
        final String server;
        OnlinePlayer(String nickname, String server) { this.nickname = nickname; this.server = server; }
    }
}
