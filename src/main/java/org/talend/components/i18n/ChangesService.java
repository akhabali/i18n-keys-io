package org.talend.components.i18n;

import static java.util.stream.Collectors.toMap;
import static org.eclipse.jgit.diff.DiffEntry.ChangeType.ADD;
import static org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.AbstractMap;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ChangesService implements AutoCloseable {

    private final static String HEAD = "HEAD";

    private final String repositoryPath;

    private final String outDirPath;

    private final Configuration config;

    private Git git;

    public void open() {
        try {
            git = Git.open(new File(repositoryPath));
        } catch (IOException e) {
            throw new IllegalStateException("Can't open git repository", e);
        }
    }

    @Override
    public void close() {
        if (git != null) {
            git.close();
        }
    }

    public void exportChanged(final String since) {
        final File outDir = new File(outDirPath);
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                throw new IllegalStateException("Can't create output directory in '" + outDirPath + "'");
            }
        }
        final Map<String, Properties> changesByFile = effectiveChanges(since);
        final Properties mapping = new Properties();
        final AtomicInteger counter = new AtomicInteger(1);
        final AtomicInteger keysCounter = new AtomicInteger(0);
        changesByFile.forEach((k, changes) -> {
            try {
                keysCounter.addAndGet(changes.size());
                String name = counter.getAndIncrement() + "-" + k.substring(k.lastIndexOf("/") + 1);
                changes.store(new FileWriter(new File(outDir, name)), null);
                mapping.put(name, k);
            } catch (IOException e) {
                log.error("Can't extract file " + k, e);
            }
        });
        try {
            mapping.put("metadata.git.repository.path", repositoryPath);
            mapping.put("metadata.extraction.since", since);
            mapping.put("metadata.total.file", String.valueOf(counter.get()));
            mapping.put("metadata.total.keys", String.valueOf(keysCounter.get()));
            mapping.store(new FileWriter(new File(outDir, "metadata")), null);
        } catch (IOException e) {
            throw new IllegalStateException("Can't create metadata file");
        }
    }

    private Map<String, Properties> effectiveChanges(final String since) {
        final DiffOutputStream out = new DiffOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(git.getRepository());
            Map<String, Map<DiffEntry.ChangeType, Properties>> patch = new HashMap<>();
            changes(since, ".properties").forEach(entry -> {
                try {
                    formatter.format(entry);
                    final List<String> changes;
                    try (final BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(out.getDiff().toString().getBytes(StandardCharsets.UTF_8))))) {
                        changes = br.lines().collect(Collectors.toList());
                    }
                    switch (entry.getChangeType()) {
                    case ADD:
                        patch.putIfAbsent(entry.getNewPath(), new HashMap<>());
                        patch.get(entry.getNewPath()).putIfAbsent(ADD, new Properties());
                        patch.get(entry.getNewPath()).get(ADD).putAll(extractAdditions(entry, changes));
                        break;
                    case DELETE:
                        patch.putIfAbsent(entry.getNewPath(), new HashMap<>());
                        patch.get(entry.getNewPath()).putIfAbsent(DELETE, new Properties());
                        patch.get(entry.getNewPath()).get(DELETE).putAll(extractDeletions(entry, changes));
                        break;
                    case MODIFY:
                        patch.putIfAbsent(entry.getNewPath(), new HashMap<>());
                        patch.get(entry.getNewPath()).putIfAbsent(ADD, new Properties());
                        patch.get(entry.getNewPath()).get(ADD).putAll(extractAdditions(entry, changes));
                        patch.putIfAbsent(entry.getNewPath(), new HashMap<>());
                        patch.get(entry.getNewPath()).putIfAbsent(DELETE, new Properties());
                        patch.get(entry.getNewPath()).get(DELETE).putAll(extractDeletions(entry, changes));
                        break;
                    case RENAME:
                        break;
                    case COPY:
                        break;
                    default:
                        break;
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    out.reset();
                }
            });
            Map<String, Properties> effectiveChanges = new HashMap<>();
            patch.forEach((filePath, changes) -> {
                Properties properties = new Properties();
                if (changes.containsKey(ADD) && !changes.get(ADD).isEmpty()) {
                    changes.get(ADD).entrySet().stream()
                            .filter(kv -> !changes.containsKey(DELETE)
                                    || !changes.get(DELETE).containsKey(kv.getKey())
                                    || !changes.get(DELETE).get(kv.getKey()).equals(kv.getValue()) // not the same value between add/delete
                            )
                            .filter(kv -> !String.valueOf(kv.getValue()).isEmpty())
                            .forEach(kv -> properties.put(kv.getKey(), kv.getValue()));
                    if (!properties.isEmpty()) {
                        effectiveChanges.put(filePath, properties);
                    }
                }
            });

            return effectiveChanges;
        }
    }

    private Map<String, String> extractAdditions(final DiffEntry entry, final List<String> changes) {
        return changes.stream().filter(l -> !l.isEmpty() && !l.substring(1).trim().isEmpty())
                .filter(l -> !l.startsWith("+++"))
                .filter(this::notComment)
                .filter(l -> l.startsWith("+"))
                .map(this::toKeyValue)
                .map(kv -> new AbstractMap.SimpleEntry<>(kv[0], kv.length == 2 ? kv[1] : ""))
                .collect(toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue, (k, k2) -> {
                    log.warn("duplicated key '" + k + "' found in " + entry.getNewPath());
                    return k2;
                }));
    }

    private Map<String, String> extractDeletions(final DiffEntry entry, final List<String> changes) {
        return changes.stream().filter(l -> !l.isEmpty() && !l.substring(1).trim().isEmpty())
                .filter(l -> !l.startsWith("---"))
                .filter(this::notComment)
                .filter(l -> l.startsWith("-"))
                .map(this::toKeyValue)
                .map(kv -> new AbstractMap.SimpleEntry<>(kv[0], kv.length == 2 ? kv[1] : ""))
                .collect(toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue, (k, k2) -> {
                    log.warn("duplicated key '" + k + "' found in " + entry.getNewPath());
                    return k2;
                }));
    }

    private String[] toKeyValue(final String line) {
        return line.substring(1).trim().split("=");
    }

    private boolean notComment(final String line) {
        return !line.substring(1).trim().startsWith("#");
    }

    private List<DiffEntry> changes(String since, String fileSuffix) {
        try {
            final Repository repository = git.getRepository();
            final DiffCommand cmd = git.diff();
            final AbstractTreeIterator headTree = prepareTreeParser(repository, repository.resolve(HEAD));
            final AbstractTreeIterator oldTree = prepareTreeParser(repository, commitSince(since));
            cmd.setOldTree(oldTree).setNewTree(headTree).setShowNameAndStatusOnly(false);
            if (fileSuffix != null && !fileSuffix.isEmpty()) {
                cmd.setPathFilter(PathSuffixFilter.create(fileSuffix));
            }
            try {
                return cmd.call().stream()
                        .filter(diffEntry -> isTranslatable(diffEntry.getNewPath()))
                        .collect(Collectors.toList());
            } catch (GitAPIException e) {
                throw new IllegalStateException("Can't get the file diff", e);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isTranslatable(String file) {
        return file.endsWith(".properties")
                && file.contains("/src/main/resources/")
                && !file.contains("_fr.properties")
                && !file.contains("_ja.properties")
                && !file.contains("_en.properties")
                && !file.contains("jndi")
                && !file.contains("log4j")
                && !file.contains("pom");
    }

    @Data
    private static class DiffOutputStream extends OutputStream {

        private StringBuilder diff = new StringBuilder();

        @Override
        public void write(final int b) {
            int[] bytes = { b };
            diff.append(new String(bytes, 0, bytes.length));
        }

        void reset() {
            diff = new StringBuilder();
        }
    }

    private ObjectId commitSince(String since) {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(config.getTimeZone());
        calendar.setLenient(false);
        final Date sinceDate;
        try {
            calendar.setTime(config.getDateFormat().parse(since));
            sinceDate = calendar.getTime();
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }

        try {
            final LogCommand cmd = git.log();
            ObjectId ref = null;
            final Iterable<RevCommit> all = cmd.call();
            for (final RevCommit revCommit : all) {
                calendar.setTimeInMillis(revCommit.getCommitTime() * 1000L);
                if (sinceDate.after(calendar.getTime())) {
                    break;
                }

                ref = revCommit;
            }

            if (ref == null) {
                throw new IllegalStateException("Can't find any commit since '" + since + "'");
            }
            return ref;
        } catch (GitAPIException e) {
            throw new IllegalStateException(e);
        }

    }

    private static AbstractTreeIterator prepareTreeParser(final Repository repository, final ObjectId objectId) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(objectId);
            RevTree tree = walk.parseTree(commit.getTree().getId());
            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }
            walk.dispose();
            return treeParser;
        }
    }
}
