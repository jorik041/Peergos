package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.fs.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

@JsType
public class SocialPost implements Cborable {
    @JsType
    public enum Type {
        Text, // body is just text
        Image, // 1 image in references
        Video, // 1 video in references
        Audio, // 1 audio file in references
        Media, // several images/video/audio files in references
        Poll // votes are merged in as comments, options are in body
    }

    /** This enum describes the audience that a post is allowed to be shared with.
     *
     */
    @JsType
    public enum Resharing {
        Author,
        Friends,
        Followers,
        Public
    }

    public interface Content extends Cborable {

        @JsMethod
        String inlineText();

        @JsMethod
        Optional<Ref> reference();

        @JsType
        class Text implements Content {
            public final String content;

            public Text(String content) {
                this.content = content;
            }

            @Override
            public String inlineText() {
                return content;
            }

            @Override
            public Optional<Ref> reference() {
                return Optional.empty();
            }

            @Override
            public CborObject toCbor() {
                return new CborObject.CborString(content);
            }
        }

        class Reference implements Content {
            public final Ref ref;

            public Reference(Ref ref) {
                this.ref = ref;
            }

            @Override
            public CborObject toCbor() {
                SortedMap<String, Cborable> state = new TreeMap<>();
                state.put("t", new CborObject.CborString("Ref"));
                state.put("r", ref);
                return CborObject.CborMap.build(state);
            }

            @Override
            public String inlineText() {
                return "";
            }

            @Override
            public Optional<Ref> reference() {
                return Optional.of(ref);
            }
        }

        static Content fromCbor(Cborable cbor) {
            if (cbor instanceof CborObject.CborString)
                return new Text(((CborObject.CborString) cbor).value);
            if (cbor instanceof CborObject.CborMap) {
                CborObject.CborMap m = (CborObject.CborMap) cbor;
                String type = m.getString("t");
                switch (type) {
                    case "Ref": return new Reference(m.get("r", Ref::fromCbor));
                    default: throw new IllegalStateException("Unknown content type in Social Post: " + type);
                }
            }
            throw new IllegalStateException("Unknown Content type in Social Post");
        }
    }

    public final Type kind;
    public final String author;
    public final List<? extends Content> body;
    public final LocalDateTime postTime;
    public final Resharing shareTo;
    public final Optional<Ref> parent;
    public final List<SocialPost> previousVersions;
    // this is excluded from hash calculation when replying
    public final List<Ref> comments;

    @JsConstructor
    public SocialPost(Type kind,
                      String author,
                      List<? extends Content> body,
                      LocalDateTime postTime,
                      Resharing shareTo,
                      Optional<Ref> parent,
                      List<SocialPost> previousVersions,
                      List<Ref> comments) {
        this.kind = kind;
        this.author = author;
        this.body = body;
        this.postTime = postTime;
        this.shareTo = shareTo;
        this.parent = parent;
        this.previousVersions = previousVersions;
        this.comments = comments;
    }

    @JsMethod
    public List<Ref> references() {
        return body.stream()
                .flatMap(c -> c.reference().stream())
                .collect(Collectors.toList());
    }

    public static SocialPost createInitialPost(Type type, String author, List<? extends Content> body, Resharing resharing) {
        return new SocialPost(type, author, body, LocalDateTime.now(), resharing,
                Optional.empty(), Collections.emptyList(), Collections.emptyList());
    }

    public static SocialPost createComment(Ref parent, Resharing fromParent, Type type, String author, List<? extends Content> body) {
        return new SocialPost(type, author, body, LocalDateTime.now(), fromParent,
                Optional.of(parent), Collections.emptyList(), Collections.emptyList());
    }

    public SocialPost edit(List<? extends Content> body,
                           LocalDateTime postTime) {
        ArrayList<SocialPost> versions = new ArrayList<>(previousVersions);
        versions.add(this);
        return new SocialPost(kind, author, body, postTime, shareTo, parent, versions, comments);
    }

    /** adding references to comments does not change the version of this comment (the hash ignores the comment refs)
     *
     * @param comment
     * @return
     */
    public SocialPost addComment(Ref comment) {
        ArrayList<Ref> updatedComments = new ArrayList<>(comments);
        if (! comments.contains(comment))
            updatedComments.add(comment);
        return new SocialPost(kind, author, body, postTime, shareTo, parent, previousVersions, updatedComments);
    }

    public SocialPost addComments(List<Ref> newComments) {
        ArrayList<Ref> updatedComments = new ArrayList<>(comments);
        for (Ref comment : newComments) {
            if (!updatedComments.contains(comment))
                updatedComments.add(comment);
        }
        return new SocialPost(kind, author, body, postTime, shareTo, parent, previousVersions, updatedComments);
    }

    private byte[] serializeWithoutComments() {
        return new SocialPost(kind, author, body, postTime, shareTo, parent,
                previousVersions, Collections.emptyList()).serialize();
    }

    public CompletableFuture<Multihash> contentHash(Hasher h) {
        return h.bareHash(serializeWithoutComments());
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("k", new CborObject.CborString(kind.name()));
        state.put("a", new CborObject.CborString(author));
        state.put("b", new CborObject.CborList(body));
        state.put("t", new CborObject.CborLong(postTime.toEpochSecond(ZoneOffset.UTC)));
        state.put("s", new CborObject.CborString(shareTo.name()));
        parent.ifPresent(r -> state.put("p", r));
        if (! previousVersions.isEmpty())
            state.put("v", new CborObject.CborList(previousVersions));
        if (! comments.isEmpty())
            state.put("d", new CborObject.CborList(comments));

        List<CborObject> withMimeType = new ArrayList<>();
        withMimeType.add(new CborObject.CborLong(MimeTypes.CBOR_PEERGOS_POST_INT));
        withMimeType.add(CborObject.CborMap.build(state));

        return new CborObject.CborList(withMimeType);
    }

    public static SocialPost fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborList withMimeType = (CborObject.CborList) cbor;
        long mimeType = withMimeType.getLong(0);
        if (mimeType != MimeTypes.CBOR_PEERGOS_POST_INT)
            throw new IllegalStateException("Invalid mimetype for SocialPost: " + mimeType);

        CborObject.CborMap m = withMimeType.get(1, c -> (CborObject.CborMap)c);

        Type type = Type.valueOf(m.getString("k"));
        String author = m.getString("a");
        List<Content> body = m.getList("b", Content::fromCbor);
        LocalDateTime postTime = m.get("t", c -> LocalDateTime.ofEpochSecond(((CborObject.CborLong)c).value, 0, ZoneOffset.UTC));
        Resharing shareTo = Resharing.valueOf(m.getString("s"));
        Optional<Ref> parent = m.getOptional("p", Ref::fromCbor);
        List<SocialPost> previousVersions = m.getList("v", SocialPost::fromCbor);
        List<Ref> comments = m.getList("d", Ref::fromCbor);

        return new SocialPost(type, author, body, postTime, shareTo, parent, previousVersions, comments);
    }

    public static class Ref implements Cborable {
        public final String path;
        public final AbsoluteCapability cap;
        public final Multihash contentHash;

        @JsConstructor
        public Ref(String path, AbsoluteCapability cap, Multihash contentHash) {
            this.path = path;
            this.cap = cap;
            this.contentHash = contentHash;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("p", new CborObject.CborString(path));
            state.put("c", cap);
            state.put("h", new CborObject.CborMerkleLink(contentHash));

            return CborObject.CborMap.build(state);
        }

        public static Ref fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;

            String path = m.getString("p");
            AbsoluteCapability cap = m.get("c", AbsoluteCapability::fromCbor);
            Multihash contentHash = m.getMerkleLink("h");
            return new Ref(path, cap, contentHash);
        }
    }

    public static class MutableRef implements Cborable {
        public final String path;
        public final AbsoluteCapability cap;

        @JsConstructor
        public MutableRef(String path, AbsoluteCapability cap) {
            this.path = path;
            this.cap = cap;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("p", new CborObject.CborString(path));
            state.put("c", cap);
            return CborObject.CborMap.build(state);
        }

        public static MutableRef fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;

            String path = m.getString("p");
            AbsoluteCapability cap = m.get("c", AbsoluteCapability::fromCbor);
            return new MutableRef(path, cap);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MutableRef that = (MutableRef) o;
            return path.equals(that.path) && cap.equals(that.cap);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, cap);
        }
    }
}
