/*
 * Copyright (c) 2013, 2014 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.source.*;
import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.utilities.BranchProfile;
import org.jruby.runtime.Visibility;
import org.jruby.truffle.nodes.RubyRootNode;
import org.jruby.truffle.nodes.dispatch.DispatchHeadNode;
import org.jruby.truffle.nodes.dispatch.PredicateDispatchHeadNode;
import org.jruby.truffle.nodes.yield.YieldDispatchHeadNode;
import org.jruby.truffle.runtime.*;
import org.jruby.truffle.runtime.core.*;
import org.jruby.truffle.runtime.core.RubyArray;
import org.jruby.truffle.runtime.core.RubyHash;
import org.jruby.truffle.runtime.hash.Bucket;
import org.jruby.truffle.runtime.hash.BucketSearchResult;
import org.jruby.truffle.runtime.hash.Entry;
import org.jruby.truffle.runtime.hash.HashOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@CoreClass(name = "Hash")
public abstract class HashNodes {

    @CoreMethod(names = "==", required = 1)
    public abstract static class EqualNode extends HashCoreMethodNode {

        @Child protected PredicateDispatchHeadNode equalNode;

        public EqualNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            equalNode = new PredicateDispatchHeadNode(context);
        }

        public EqualNode(EqualNode prev) {
            super(prev);
            equalNode = prev.equalNode;
        }

        @Specialization(guards = {"isNull", "isNull(arguments[1])"})
        public boolean equalNull(RubyHash a, RubyHash b) {
            return true;
        }

        @Specialization
        public boolean equal(VirtualFrame frame, RubyHash a, RubyHash b) {
            notDesignedForCompilation();

            final List<Entry> aEntries = HashOperations.verySlowToEntries(a);
            final List<Entry> bEntries = HashOperations.verySlowToEntries(a);

            if (aEntries.size() != bEntries.size()) {
                return false;
            }

            // For each entry in a, check that there is a corresponding entry in b, and don't use entries in b more than once

            final boolean[] bUsed = new boolean[bEntries.size()];

            for (Entry aEntry : aEntries) {
                boolean found = false;

                for (int n = 0; n < bEntries.size(); n++) {
                    if (!bUsed[n]) {
                        // TODO: cast

                        if ((boolean) DebugOperations.send(getContext(), aEntry.getKey(), "eql?", null, bEntries.get(n).getKey())) {
                            bUsed[n] = true;
                            found = true;
                            break;
                        }
                    }
                }

                if (!found) {
                    return false;
                }
            }

            return true;
        }
    }

    @CoreMethod(names = "[]", onSingleton = true, argumentsAsArray = true)
    public abstract static class ConstructNode extends HashCoreMethodNode {

        private final BranchProfile singleObject = new BranchProfile();
        private final BranchProfile singleArray = new BranchProfile();
        private final BranchProfile objectArray = new BranchProfile();
        private final BranchProfile smallObjectArray = new BranchProfile();
        private final BranchProfile largeObjectArray = new BranchProfile();
        private final BranchProfile otherArray = new BranchProfile();
        private final BranchProfile singleOther = new BranchProfile();
        private final BranchProfile keyValues = new BranchProfile();

        public ConstructNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public ConstructNode(ConstructNode prev) {
            super(prev);
        }

        @ExplodeLoop
        @Specialization
        public RubyHash construct(Object[] args) {
            if (args.length == 1) {
                singleObject.enter();

                final Object arg = args[0];

                if (arg instanceof RubyArray) {
                    singleArray.enter();

                    final RubyArray array = (RubyArray) arg;

                    if (array.getStore() instanceof Object[]) {
                        objectArray.enter();

                        final Object[] store = (Object[]) array.getStore();

                        // TODO(CS): zero length arrays might be a good specialisation

                        if (store.length <= HashOperations.SMALL_HASH_SIZE) {
                            smallObjectArray.enter();

                            final int size = store.length;
                            final Object[] newStore = new Object[HashOperations.SMALL_HASH_SIZE * 2];

                            for (int n = 0; n < HashOperations.SMALL_HASH_SIZE; n++) {
                                if (n < size) {
                                    final Object pair = store[n];

                                    if (!(pair instanceof RubyArray)) {
                                        CompilerDirectives.transferToInterpreter();
                                        throw new UnsupportedOperationException();
                                    }

                                    final RubyArray pairArray = (RubyArray) pair;

                                    if (!(pairArray.getStore() instanceof Object[])) {
                                        CompilerDirectives.transferToInterpreter();
                                        throw new UnsupportedOperationException();
                                    }

                                    final Object[] pairStore = (Object[]) pairArray.getStore();

                                    newStore[n * 2] = pairStore[0];
                                    newStore[n * 2 + 1] = pairStore[1];
                                }
                            }

                            return new RubyHash(getContext().getCoreLibrary().getHashClass(), null, null, newStore, size, null);
                        } else {
                            largeObjectArray.enter();
                            throw new UnsupportedOperationException();
                        }
                    } else {
                        otherArray.enter();
                        throw new UnsupportedOperationException();
                    }
                } else {
                    singleOther.enter();
                    throw new UnsupportedOperationException();
                }
            } else {
                keyValues.enter();

                final List<Entry> entries = new ArrayList<>();

                for (int n = 0; n < args.length; n += 2) {
                    entries.add(new Entry(args[n], args[n + 1]));
                }

                return HashOperations.verySlowFromEntries(getContext(), entries);
            }
        }

    }

    @CoreMethod(names = "[]", required = 1)
    public abstract static class GetIndexNode extends HashCoreMethodNode {

        @Child protected PredicateDispatchHeadNode eqlNode;
        @Child protected YieldDispatchHeadNode yield;

        private final BranchProfile notInHashProfile = new BranchProfile();
        private final BranchProfile useDefaultProfile = new BranchProfile();

        public GetIndexNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            eqlNode = new PredicateDispatchHeadNode(context);
            yield = new YieldDispatchHeadNode(context);
        }

        public GetIndexNode(GetIndexNode prev) {
            super(prev);
            eqlNode = prev.eqlNode;
            yield = prev.yield;
        }

        @Specialization(guards = "isNull")
        public Object getNull(VirtualFrame frame, RubyHash hash, Object key) {
            notDesignedForCompilation();

            if (hash.getDefaultBlock() != null) {
                return yield.dispatch(frame, hash.getDefaultBlock(), hash, key);
            } else if (hash.getDefaultValue() != null) {
                return hash.getDefaultValue();
            } else {
                return getContext().getCoreLibrary().getNilObject();
            }
        }

        @ExplodeLoop
        @Specialization(guards = "isObjectArray")
        public Object getObjectArray(VirtualFrame frame, RubyHash hash, Object key) {
            final Object[] store = (Object[]) hash.getStore();
            final int size = hash.getStoreSize();

            for (int n = 0; n < HashOperations.SMALL_HASH_SIZE; n++) {
                if (n < size && eqlNode.call(frame, store[n * 2], "eql?", null, key)) {
                    return store[n * 2 + 1];
                }
            }

            notInHashProfile.enter();

            if (hash.getDefaultBlock() != null) {
                useDefaultProfile.enter();
                return yield.dispatch(frame, hash.getDefaultBlock(), hash, key);
            }

            if (hash.getDefaultValue() != null) {
                return hash.getDefaultValue();
            }

            return getContext().getCoreLibrary().getNilObject();

        }

        @Specialization(guards = "isBucketArray")
        public Object getBucketArray(VirtualFrame frame, RubyHash hash, Object key) {
            notDesignedForCompilation();

            final BucketSearchResult bucketSearchResult = HashOperations.verySlowFindBucket(hash, key);

            if (bucketSearchResult.getBucket() != null) {
                return bucketSearchResult.getBucket().getValue();
            }

            notInHashProfile.enter();

            if (hash.getDefaultBlock() != null) {
                useDefaultProfile.enter();
                return yield.dispatch(frame, hash.getDefaultBlock(), hash, key);
            }

            if (hash.getDefaultValue() != null) {
                return hash.getDefaultValue();
            }

            return getContext().getCoreLibrary().getNilObject();
        }

    }

    @CoreMethod(names = "[]=", required = 2)
    public abstract static class SetIndexNode extends HashCoreMethodNode {

        @Child protected PredicateDispatchHeadNode eqlNode;

        private final BranchProfile considerExtendProfile = new BranchProfile();
        private final BranchProfile extendProfile = new BranchProfile();

        public SetIndexNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            eqlNode = new PredicateDispatchHeadNode(context);
        }

        public SetIndexNode(SetIndexNode prev) {
            super(prev);
            eqlNode = prev.eqlNode;
        }

        @Specialization(guards = "isNull")
        public Object setNull(RubyHash hash, Object key, Object value) {
            hash.checkFrozen(this);
            final Object[] store = new Object[HashOperations.SMALL_HASH_SIZE * 2];
            store[0] = key;
            store[1] = value;
            hash.setStore(store, 1, null, null);
            return value;
        }

        @ExplodeLoop
        @Specialization(guards = "isObjectArray")
        public Object setObjectArray(VirtualFrame frame, RubyHash hash, Object key, Object value) {
            hash.checkFrozen(this);

            final Object[] store = (Object[]) hash.getStore();
            final int size = hash.getStoreSize();

            for (int n = 0; n < HashOperations.SMALL_HASH_SIZE; n++) {
                if (n < size && eqlNode.call(frame, store[n * 2], "eql?", null, key)) {
                    store[n * 2 + 1] = value;
                    return value;
                }
            }

            considerExtendProfile.enter();

            final int newSize = size + 1;

            if (newSize <= HashOperations.SMALL_HASH_SIZE) {
                extendProfile.enter();
                store[size * 2] = key;
                store[size * 2 + 1] = value;
                hash.setStoreSize(newSize);
                return value;
            }

            CompilerDirectives.transferToInterpreter();

            // TODO(CS): need to watch for that transfer until we make the following fast path

            final List<Entry> entries = HashOperations.verySlowToEntries(hash);

            hash.setStore(new Bucket[HashOperations.capacityGreaterThan(newSize)], newSize, null, null);

            for (Entry entry : entries) {
                HashOperations.verySlowSetInBuckets(hash, entry.getKey(), entry.getValue());
            }

            HashOperations.verySlowSetInBuckets(hash, key, value);

            return value;
        }

        @Specialization(guards = "isBucketArray")
        public Object setBucketArray(RubyHash hash, Object key, Object value) {
            notDesignedForCompilation();

            if (HashOperations.verySlowSetInBuckets(hash, key, value)) {
                hash.setStoreSize(hash.getStoreSize() + 1);
            }

            return value;
        }

    }

    @CoreMethod(names = "clear")
    public abstract static class ClearNode extends HashCoreMethodNode {

        public ClearNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public ClearNode(ClearNode prev) {
            super(prev);
        }

        @Specialization(guards = "isNull")
        public RubyHash emptyNull(RubyHash hash) {
            return hash;
        }

        @Specialization(guards = "!isNull")
        public RubyHash empty(RubyHash hash) {
            hash.setStore(null, 0, null, null);
            return hash;
        }

    }

    @CoreMethod(names = "delete", required = 1)
    public abstract static class DeleteNode extends HashCoreMethodNode {

        @Child protected PredicateDispatchHeadNode eqlNode;

        public DeleteNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            eqlNode = new PredicateDispatchHeadNode(context);
        }

        public DeleteNode(DeleteNode prev) {
            super(prev);
            eqlNode = prev.eqlNode;
        }

        @Specialization(guards = "isNull")
        public RubyNilClass deleteNull(RubyHash hash, Object key) {
            hash.checkFrozen(this);
            return getContext().getCoreLibrary().getNilObject();
        }

        @Specialization(guards = "isObjectArray")
        public Object deleteObjectArray(VirtualFrame frame, RubyHash hash, Object key) {
            hash.checkFrozen(this);

            final Object[] store = (Object[]) hash.getStore();
            final int size = hash.getStoreSize();

            for (int n = 0; n < HashOperations.SMALL_HASH_SIZE * 2; n += 2) {
                if (n < size && eqlNode.call(frame, store[n], "eql?", null, key)) {
                    final Object value = store[n + 1];

                    // Move the later values down
                    System.arraycopy(store, n + 2, store, n, HashOperations.SMALL_HASH_SIZE * 2 - n - 2);

                    hash.setStoreSize(size - 1);

                    return value;
                }
            }

            return getContext().getCoreLibrary().getNilObject();
        }

        @Specialization(guards = "isBucketArray")
        public Object delete(RubyHash hash, Object key) {
            notDesignedForCompilation();

            final BucketSearchResult bucketSearchResult = HashOperations.verySlowFindBucket(hash, key);

            if (bucketSearchResult.getBucket() == null) {
                return getContext().getCoreLibrary().getNilObject();
            }

            final Bucket bucket = bucketSearchResult.getBucket();

            // Remove from the sequence chain

            if (bucket.getPreviousInSequence() == null) {
                hash.setFirstInSequence(bucket.getNextInSequence());
            } else {
                bucket.getPreviousInSequence().setNextInSequence(bucket.getNextInSequence());
            }

            if (bucket.getNextInSequence() == null) {
                hash.setLastInSequence(bucket.getPreviousInSequence());
            } else {
                bucket.getNextInSequence().setPreviousInSequence(bucket.getPreviousInSequence());
            }

            // Remove from the lookup chain

            if (bucket.getPreviousInLookup() == null) {
                ((Bucket[]) hash.getStore())[bucketSearchResult.getIndex()] = bucket.getNextInLookup();
            } else {
                bucket.getPreviousInLookup().setNextInLookup(bucket.getNextInLookup());
            }

            if (bucket.getNextInLookup() != null) {
                bucket.getNextInLookup().setPreviousInLookup(bucket.getPreviousInLookup());
            }

            hash.setStoreSize(hash.getStoreSize() - 1);

            return bucket.getValue();
        }

    }

    @CoreMethod(names = "each", needsBlock = true)
    @ImportGuards(HashGuards.class)
    public abstract static class EachNode extends YieldingCoreMethodNode {

        public EachNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public EachNode(EachNode prev) {
            super(prev);
        }

        @Specialization(guards = "isNull")
        public RubyHash eachNull(RubyHash hash, RubyProc block) {
            notDesignedForCompilation();

            return hash;
        }

        @ExplodeLoop
        @Specialization(guards = "isObjectArray")
        public RubyHash eachObjectArray(VirtualFrame frame, RubyHash hash, RubyProc block) {
            notDesignedForCompilation();

            final Object[] store = (Object[]) hash.getStore();
            final int size = hash.getStoreSize();

            int count = 0;

            try {
                for (int n = 0; n < HashOperations.SMALL_HASH_SIZE; n++) {
                    if (CompilerDirectives.inInterpreter()) {
                        count++;
                    }

                    if (n < size) {
                        yield(frame, block, RubyArray.fromObjects(getContext().getCoreLibrary().getArrayClass(), store[n * 2], store[n * 2 + 1]));
                    }
                }
            } finally {
                if (CompilerDirectives.inInterpreter()) {
                    ((RubyRootNode) getRootNode()).reportLoopCountThroughBlocks(count);
                }
            }

            return hash;
        }

        @Specialization(guards = "isBucketArray")
        public RubyHash eachBucketArray(VirtualFrame frame, RubyHash hash, RubyProc block) {
            notDesignedForCompilation();

            for (Entry entry : HashOperations.verySlowToEntries(hash)) {
                yield(frame, block, RubyArray.fromObjects(getContext().getCoreLibrary().getArrayClass(), entry.getKey(), entry.getValue()));
            }

            return hash;
        }

    }

    @CoreMethod(names = "empty?")
    public abstract static class EmptyNode extends HashCoreMethodNode {

        public EmptyNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public EmptyNode(EmptyNode prev) {
            super(prev);
        }

        @Specialization(guards = "isNull")
        public boolean emptyNull(RubyHash hash) {
            return true;
        }

        @Specialization(guards = "!isNull")
        public boolean emptyObjectArray(RubyHash hash) {
            return hash.getStoreSize() == 0;
        }

    }

    @CoreMethod(names = "initialize", needsBlock = true, optional = 1)
    public abstract static class InitializeNode extends HashCoreMethodNode {

        public InitializeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public InitializeNode(InitializeNode prev) {
            super(prev);
        }

        @Specialization
        public RubyNilClass initialize(RubyHash hash, UndefinedPlaceholder defaultValue, UndefinedPlaceholder block) {
            notDesignedForCompilation();
            hash.setStore(null, 0, null, null);
            hash.setDefaultBlock(null);
            return getContext().getCoreLibrary().getNilObject();
        }

        @Specialization
        public RubyNilClass initialize(RubyHash hash, UndefinedPlaceholder defaultValue, RubyProc block) {
            notDesignedForCompilation();
            hash.setStore(null, 0, null, null);
            hash.setDefaultBlock(block);
            return getContext().getCoreLibrary().getNilObject();
        }

        @Specialization
        public RubyNilClass initialize(RubyHash hash, Object defaultValue, UndefinedPlaceholder block) {
            notDesignedForCompilation();
            hash.setDefaultValue(defaultValue);
            return getContext().getCoreLibrary().getNilObject();
        }

    }

    @CoreMethod(names = "initialize_copy", visibility = Visibility.PRIVATE, required = 1)
    public abstract static class InitializeCopyNode extends HashCoreMethodNode {

        public InitializeCopyNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public InitializeCopyNode(InitializeCopyNode prev) {
            super(prev);
        }

        @Specialization(guards = "isNull(arguments[1])")
        public RubyHash dupNull(RubyHash self, RubyHash from) {
            notDesignedForCompilation();

            if (self == from) {
                return self;
            }

            self.setDefaultBlock(from.getDefaultBlock());
            self.setDefaultValue(from.getDefaultValue());
            self.setStore(null, 0, null, null);

            return self;
        }

        @Specialization(guards = "isObjectArray(arguments[1])")
        public RubyHash dupObjectArray(RubyHash self, RubyHash from) {
            notDesignedForCompilation();

            if (self == from) {
                return self;
            }

            final Object[] store = (Object[]) from.getStore();
            self.setStore(Arrays.copyOf(store, HashOperations.SMALL_HASH_SIZE * 2), store.length, null, null);
            self.setDefaultBlock(from.getDefaultBlock());
            self.setDefaultValue(from.getDefaultValue());

            return self;
        }

        @Specialization(guards = "isBucketArray(arguments[1])")
        public RubyHash dupBucketArray(RubyHash self, RubyHash from) {
            notDesignedForCompilation();

            if (self == from) {
                return self;
            }

            HashOperations.verySlowSetEntries(self, HashOperations.verySlowToEntries(from));

            return self;
        }

    }

    @CoreMethod(names = {"inspect", "to_s"})
    public abstract static class InspectNode extends HashCoreMethodNode {

        @Child protected DispatchHeadNode inspect;

        public InspectNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            inspect = new DispatchHeadNode(context);
        }

        public InspectNode(InspectNode prev) {
            super(prev);
            inspect = prev.inspect;
        }

        @Specialization(guards = "isNull")
        public RubyString inspectNull(RubyHash hash) {
            notDesignedForCompilation();

            return getContext().makeString("{}");
        }

        @Specialization
        public RubyString inspectObjectArray(VirtualFrame frame, RubyHash hash) {
            notDesignedForCompilation();

            final StringBuilder builder = new StringBuilder();

            builder.append("{");

            for (Entry entry : HashOperations.verySlowToEntries(hash)) {
                if (builder.length() > 1) {
                    builder.append(", ");
                }

                // TODO(CS): to string

                builder.append(inspect.call(frame, entry.getKey(), "inspect", null));
                builder.append("=>");
                builder.append(inspect.call(frame, entry.getValue(), "inspect", null));
            }

            builder.append("}");

            return getContext().makeString(builder.toString());
        }

    }

    @CoreMethod(names = "key?", required = 1)
    public abstract static class KeyNode extends HashCoreMethodNode {

        @Child protected PredicateDispatchHeadNode eqlNode;

        public KeyNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            eqlNode = new PredicateDispatchHeadNode(context);
        }

        public KeyNode(KeyNode prev) {
            super(prev);
            eqlNode = prev.eqlNode;
        }

        @Specialization(guards = "isNull")
        public boolean keyNull(RubyHash hash, Object key) {
            return false;
        }

        @Specialization(guards = "isObjectArray")
        public boolean keyObjectArray(VirtualFrame frame, RubyHash hash, Object key) {
            notDesignedForCompilation();

            final int size = hash.getStoreSize();
            final Object[] store = (Object[]) hash.getStore();

            for (int n = 0; n < store.length; n += 2) {
                if (n < size && eqlNode.call(frame, store[n], "eql?", null, key)) {
                    return true;
                }
            }

            return false;
        }

        @Specialization(guards = "isBucketArray")
        public boolean keyBucketArray(VirtualFrame frame, RubyHash hash, Object key) {
            notDesignedForCompilation();

            for (Entry entry : HashOperations.verySlowToEntries(hash)) {
                if (eqlNode.call(frame, entry.getKey(), "eql?", null, key)) {
                    return true;
                }
            }

            return false;
        }

    }

    @CoreMethod(names = "keys")
    public abstract static class KeysNode extends HashCoreMethodNode {

        public KeysNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public KeysNode(KeysNode prev) {
            super(prev);
        }

        @Specialization(guards = "isNull")
        public RubyArray keysNull(RubyHash hash) {
            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), null, 0);
        }

        @Specialization(guards = "isObjectArray")
        public RubyArray keysObjectArray(RubyHash hash) {
            notDesignedForCompilation();

            final Object[] store = (Object[]) hash.getStore();

            final Object[] keys = new Object[hash.getStoreSize()];

            for (int n = 0; n < keys.length; n++) {
                keys[n] = store[n * 2];
            }

            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), keys, keys.length);
        }

        @Specialization(guards = "isBucketArray")
        public RubyArray keysBucketArray(RubyHash hash) {
            notDesignedForCompilation();

            final Object[] keys = new Object[hash.getStoreSize()];

            Bucket bucket = hash.getFirstInSequence();
            int n = 0;

            while (bucket != null) {
                keys[n] = bucket.getKey();
                n++;
                bucket = bucket.getNextInSequence();
            }

            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), keys, keys.length);
        }

    }

    @CoreMethod(names = {"map", "collect"}, needsBlock = true)
    @ImportGuards(HashGuards.class)
    public abstract static class MapNode extends YieldingCoreMethodNode {

        public MapNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public MapNode(MapNode prev) {
            super(prev);
        }

        @ExplodeLoop
        @Specialization(guards = "isObjectArray")
        public RubyArray mapObjectArray(VirtualFrame frame, RubyHash hash, RubyProc block) {
            final Object[] store = (Object[]) hash.getStore();
            final int size = hash.getStoreSize();

            final int resultSize = store.length / 2;
            final Object[] result = new Object[resultSize];

            int count = 0;

            try {
                for (int n = 0; n < HashOperations.SMALL_HASH_SIZE; n++) {
                    if (n < size) {
                        final Object key = store[n * 2];
                        final Object value = store[n * 2 + 1];
                        result[n] = yield(frame, block, key, value);

                        if (CompilerDirectives.inInterpreter()) {
                            count++;
                        }
                    }
                }
            } finally {
                if (CompilerDirectives.inInterpreter()) {
                    ((RubyRootNode) getRootNode()).reportLoopCountThroughBlocks(count);
                }
            }

            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), result, resultSize);
        }

        @Specialization(guards = "isBucketArray")
        public RubyArray mapBucketArray(VirtualFrame frame, RubyHash hash, RubyProc block) {
            notDesignedForCompilation();

            final RubyArray array = new RubyArray(getContext().getCoreLibrary().getArrayClass(), null, 0);

            for (Entry entry : HashOperations.verySlowToEntries(hash)) {
                array.slowPush(yield(frame, block, entry.getKey(), entry.getValue()));
            }

            return array;
        }

    }

    @CoreMethod(names = "merge", required = 1)
    public abstract static class MergeNode extends HashCoreMethodNode {

        @Child protected PredicateDispatchHeadNode eqlNode;

        private final BranchProfile nothingFromFirstProfile = new BranchProfile();
        private final BranchProfile considerNothingFromSecondProfile = new BranchProfile();
        private final BranchProfile nothingFromSecondProfile = new BranchProfile();
        private final BranchProfile considerResultIsSmallProfile = new BranchProfile();
        private final BranchProfile resultIsSmallProfile = new BranchProfile();

        private final int smallHashSize = HashOperations.SMALL_HASH_SIZE;

        public MergeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            eqlNode = new PredicateDispatchHeadNode(context);
        }

        public MergeNode(MergeNode prev) {
            super(prev);
            eqlNode = prev.eqlNode;
        }

        @Specialization(guards = {"isObjectArray", "isNull(arguments[1])"})
        public RubyHash mergeObjectArrayNull(RubyHash hash, RubyHash other) {
            final Object[] store = (Object[]) hash.getStore();
            final Object[] copy = Arrays.copyOf(store, HashOperations.SMALL_HASH_SIZE * 2);

            return new RubyHash(getContext().getCoreLibrary().getHashClass(), hash.getDefaultBlock(), hash.getDefaultValue(), copy, hash.getStoreSize(), null);
        }

        @ExplodeLoop
        @Specialization(guards = {"isObjectArray", "isObjectArray(arguments[1])"})
        public RubyHash mergeObjectArrayObjectArray(VirtualFrame frame, RubyHash hash, RubyHash other) {
            // TODO(CS): what happens with the default block here? Which side does it get merged from?

            final Object[] storeA = (Object[]) hash.getStore();
            final int storeASize = hash.getStoreSize();

            final Object[] storeB = (Object[]) other.getStore();
            final int storeBSize = hash.getStoreSize();

            final boolean[] mergeFromA = new boolean[storeASize];
            int mergeFromACount = 0;

            for (int a = 0; a < HashOperations.SMALL_HASH_SIZE; a++) {
                if (a < storeASize) {
                    boolean merge = true;

                    for (int b = 0; b < HashOperations.SMALL_HASH_SIZE; b++) {
                        if (b < storeBSize) {
                            if (eqlNode.call(frame, storeA[a * 2], "eql?", null, storeB[b * 2])) {
                                merge = false;
                                break;
                            }
                        }
                    }

                    if (merge) {
                        mergeFromACount++;
                    }

                    mergeFromA[a] = merge;
                }
            }

            if (mergeFromACount == 0) {
                nothingFromFirstProfile.enter();
                return new RubyHash(getContext().getCoreLibrary().getHashClass(), hash.getDefaultBlock(), hash.getDefaultValue(), Arrays.copyOf(storeB, HashOperations.SMALL_HASH_SIZE * 2), storeBSize, null);
            }

            considerNothingFromSecondProfile.enter();

            if (mergeFromACount == storeB.length) {
                nothingFromSecondProfile.enter();
                return new RubyHash(getContext().getCoreLibrary().getHashClass(), hash.getDefaultBlock(), hash.getDefaultValue(), Arrays.copyOf(storeB, HashOperations.SMALL_HASH_SIZE * 2), storeBSize, null);
            }

            considerResultIsSmallProfile.enter();

            final int mergedSize = storeBSize + mergeFromACount;

            if (storeBSize + mergeFromACount <= smallHashSize) {
                resultIsSmallProfile.enter();

                final Object[] merged = new Object[HashOperations.SMALL_HASH_SIZE * 2];

                int index = 0;

                for (int n = 0; n < storeASize; n++) {
                    if (mergeFromA[n]) {
                        merged[index] = storeA[n * 2];
                        merged[index + 1] = storeA[n * 2 + 1];
                        index += 2;
                    }
                }

                for (int n = 0; n < storeBSize; n++) {
                    merged[index] = storeB[n * 2];
                    merged[index + 1] = storeB[n * 2 + 1];
                    index += 2;
                }

                return new RubyHash(getContext().getCoreLibrary().getHashClass(), hash.getDefaultBlock(), hash.getDefaultValue(), merged, mergedSize, null);
            }

            CompilerDirectives.transferToInterpreter();
            throw new UnsupportedOperationException();
        }


        @Specialization
        public RubyHash mergeBucketArrayBucketArray(VirtualFrame frame, RubyHash hash, RubyHash other) {
            final RubyHash merged = new RubyHash(getContext().getCoreLibrary().getHashClass(), null, null, new Bucket[HashOperations.capacityGreaterThan(hash.getStoreSize() + hash.getStoreSize())], 0, null);

            for (Entry entry : HashOperations.verySlowToEntries(hash)) {
                HashOperations.verySlowSetInBuckets(merged, entry.getKey(), entry.getValue());
            }

            for (Entry entry : HashOperations.verySlowToEntries(other)) {
                HashOperations.verySlowSetInBuckets(merged, entry.getKey(), entry.getValue());
            }

            return merged;
        }

    }

    @CoreMethod(names = "default", optional = 1)
    public abstract static class DefaultNode extends HashCoreMethodNode {

        public DefaultNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public DefaultNode(DefaultNode prev) {
            super(prev);
        }

        @Specialization
        public Object defaultElement(VirtualFrame frame, RubyHash hash, UndefinedPlaceholder undefined) {
            Object ret = hash.getDefaultValue();

            // TODO (nirvdrum Dec. 1, 2014): This needs to evaluate the defaultProc if it exists before it tries defaultValue.
            if (ret != null) {
                return ret;
            } else {
                return getContext().getCoreLibrary().getNilObject();
            }
        }

        @Specialization
        public Object defaultElement(VirtualFrame frame, RubyHash hash, Object key) {
            Object ret = hash.getDefaultValue();

            // TODO (nirvdrum Dec. 1, 2014): This really needs to do something with the key.  Dummy stub for now.
            if (ret != null) {
                return ret;
            } else {
                return getContext().getCoreLibrary().getNilObject();
            }
        }
    }

    @CoreMethod(names = "size")
    public abstract static class SizeNode extends HashCoreMethodNode {

        public SizeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public SizeNode(SizeNode prev) {
            super(prev);
        }

        @Specialization(guards = "isNull")
        public int sizeNull(RubyHash hash) {
            return 0;
        }

        @Specialization(guards = "!isNull")
        public int sizeObjectArray(RubyHash hash) {
            return hash.getStoreSize();
        }

    }

    @CoreMethod(names = "values")
    public abstract static class ValuesNode extends HashCoreMethodNode {

        public ValuesNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public ValuesNode(ValuesNode prev) {
            super(prev);
        }

        @Specialization(guards = "isNull")
        public RubyArray valuesNull(RubyHash hash) {
            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), null, 0);
        }

        @Specialization(guards = "isObjectArray")
        public RubyArray valuesObjectArray(RubyHash hash) {
            final Object[] store = (Object[]) hash.getStore();

            final Object[] values = new Object[hash.getStoreSize()];

            for (int n = 0; n < values.length; n++) {
                values[n] = store[n * 2 + 1];
            }

            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), values, values.length);
        }

        @Specialization(guards = "isBucketArray")
        public RubyArray valuesBucketArray(RubyHash hash) {
            notDesignedForCompilation();

            final Object[] values = new Object[hash.getStoreSize()];

            Bucket bucket = hash.getFirstInSequence();
            int n = 0;

            while (bucket != null) {
                values[n] = bucket.getValue();
                n++;
                bucket = bucket.getNextInSequence();
            }

            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), values, values.length);
        }

    }

    @CoreMethod(names = "to_a")
    public abstract static class ToArrayNode extends HashCoreMethodNode {

        public ToArrayNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public ToArrayNode(ToArrayNode prev) {
            super(prev);
        }

        @Specialization(guards = "isNull")
        public RubyArray toArrayNull(RubyHash hash) {
            notDesignedForCompilation();

            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), null, 0);
        }

        @Specialization(guards = "isObjectArray")
        public RubyArray toArrayObjectArray(RubyHash hash) {
            notDesignedForCompilation();

            final Object[] store = (Object[]) hash.getStore();
            final int size = hash.getStoreSize();
            final Object[] pairs = new Object[size];

            for (int n = 0; n < size; n++) {
                pairs[n] = RubyArray.fromObjects(getContext().getCoreLibrary().getArrayClass(), store[n * 2], store[n * 2 + 1]);
            }

            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), pairs, size);
        }

        @Specialization(guards = "isBucketArray")
        public RubyArray toArrayBucketArray(RubyHash hash) {
            notDesignedForCompilation();

            final int size = hash.getStoreSize();
            final Object[] pairs = new Object[size];

            int n = 0;

            for (Entry entry : HashOperations.verySlowToEntries(hash)) {
                pairs[n] = RubyArray.fromObjects(getContext().getCoreLibrary().getArrayClass(), entry.getValue(), entry.getValue());
                n++;
            }

            return new RubyArray(getContext().getCoreLibrary().getArrayClass(), pairs, size);
        }

    }

}
