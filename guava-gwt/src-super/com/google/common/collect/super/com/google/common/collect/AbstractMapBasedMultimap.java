/*
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.collect;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.CollectPreconditions.checkRemove;

import com.google.common.annotations.GwtCompatible;
import com.google.common.collect.Maps.ImprovedAbstractMap;

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.RandomAccess;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

import javax.annotation.Nullable;

/**
 * Basic implementation of the {@link Multimap} interface. This class represents
 * a multimap as a map that associates each key with a collection of values. All
 * methods of {@link Multimap} are supported, including those specified as
 * optional in the interface.
 *
 * <p>To implement a multimap, a subclass must define the method {@link
 * #createCollection()}, which creates an empty collection of values for a key.
 *
 * <p>The multimap constructor takes a map that has a single entry for each
 * distinct key. When you insert a key-value pair with a key that isn't already
 * in the multimap, {@code AbstractMapBasedMultimap} calls {@link #createCollection()}
 * to create the collection of values for that key. The subclass should not call
 * {@link #createCollection()} directly, and a new instance should be created
 * every time the method is called.
 *
 * <p>For example, the subclass could pass a {@link java.util.TreeMap} during
 * construction, and {@link #createCollection()} could return a {@link
 * java.util.TreeSet}, in which case the multimap's iterators would propagate
 * through the keys and values in sorted order.
 *
 * <p>Keys and values may be null, as long as the underlying collection classes
 * support null elements.
 *
 * <p>The collections created by {@link #createCollection()} may or may not
 * allow duplicates. If the collection, such as a {@link Set}, does not support
 * duplicates, an added key-value pair will replace an existing pair with the
 * same key and value, if such a pair is present. With collections like {@link
 * List} that allow duplicates, the collection will keep the existing key-value
 * pairs while adding a new pair.
 *
 * <p>This class is not threadsafe when any concurrent operations update the
 * multimap, even if the underlying map and {@link #createCollection()} method
 * return threadsafe classes. Concurrent read operations will work correctly. To
 * allow concurrent update operations, wrap your multimap with a call to {@link
 * Multimaps#synchronizedMultimap}.
 *
 * <p>For serialization to work, the subclass must specify explicit
 * {@code readObject} and {@code writeObject} methods.
 *
 * @author Jared Levy
 * @author Louis Wasserman
 */
@GwtCompatible(emulated = true)
abstract class AbstractMapBasedMultimap<K, V> extends AbstractMultimap<K, V>
    implements Serializable {
  /*
   * Here's an outline of the overall design.
   *
   * The map variable contains the collection of values associated with each
   * key. When a key-value pair is added to a multimap that didn't previously
   * contain any values for that key, a new collection generated by
   * createCollection is added to the map. That same collection instance
   * remains in the map as long as the multimap has any values for the key. If
   * all values for the key are removed, the key and collection are removed
   * from the map.
   *
   * The get method returns a WrappedCollection, which decorates the collection
   * in the map (if the key is present) or an empty collection (if the key is
   * not present). When the collection delegate in the WrappedCollection is
   * empty, the multimap may contain subsequently added values for that key. To
   * handle that situation, the WrappedCollection checks whether map contains
   * an entry for the provided key, and if so replaces the delegate.
   */

  private transient Map<K, Collection<V>> map;
  private transient int totalSize;

  /**
   * Creates a new multimap that uses the provided map.
   *
   * @param map place to store the mapping from each key to its corresponding
   *     values
   * @throws IllegalArgumentException if {@code map} is not empty
   */
  protected AbstractMapBasedMultimap(Map<K, Collection<V>> map) {
    checkArgument(map.isEmpty());
    this.map = map;
  }

  /** Used during deserialization only. */
  final void setMap(Map<K, Collection<V>> map) {
    this.map = map;
    totalSize = 0;
    for (Collection<V> values : map.values()) {
      checkArgument(!values.isEmpty());
      totalSize += values.size();
    }
  }

  /**
   * Creates an unmodifiable, empty collection of values.
   *
   * <p>This is used in {@link #removeAll} on an empty key.
   */
  Collection<V> createUnmodifiableEmptyCollection() {
    return unmodifiableCollectionSubclass(createCollection());
  }

  /**
   * Creates the collection of values for a single key.
   *
   * <p>Collections with weak, soft, or phantom references are not supported.
   * Each call to {@code createCollection} should create a new instance.
   *
   * <p>The returned collection class determines whether duplicate key-value
   * pairs are allowed.
   *
   * @return an empty collection of values
   */
  abstract Collection<V> createCollection();

  /**
   * Creates the collection of values for an explicitly provided key. By
   * default, it simply calls {@link #createCollection()}, which is the correct
   * behavior for most implementations. The {@link LinkedHashMultimap} class
   * overrides it.
   *
   * @param key key to associate with values in the collection
   * @return an empty collection of values
   */
  Collection<V> createCollection(@Nullable K key) {
    return createCollection();
  }

  Map<K, Collection<V>> backingMap() {
    return map;
  }

  // Query Operations

  @Override
  public int size() {
    return totalSize;
  }

  @Override
  public boolean containsKey(@Nullable Object key) {
    return map.containsKey(key);
  }

  // Modification Operations

  @Override
  public boolean put(@Nullable K key, @Nullable V value) {
    Collection<V> collection = map.get(key);
    if (collection == null) {
      collection = createCollection(key);
      if (collection.add(value)) {
        totalSize++;
        map.put(key, collection);
        return true;
      } else {
        throw new AssertionError("New Collection violated the Collection spec");
      }
    } else if (collection.add(value)) {
      totalSize++;
      return true;
    } else {
      return false;
    }
  }

  private Collection<V> getOrCreateCollection(@Nullable K key) {
    Collection<V> collection = map.get(key);
    if (collection == null) {
      collection = createCollection(key);
      map.put(key, collection);
    }
    return collection;
  }

  // Bulk Operations

  /**
   * {@inheritDoc}
   *
   * <p>The returned collection is immutable.
   */
  @Override
  public Collection<V> replaceValues(@Nullable K key, Iterable<? extends V> values) {
    Iterator<? extends V> iterator = values.iterator();
    if (!iterator.hasNext()) {
      return removeAll(key);
    }

    // TODO(user): investigate atomic failure?
    Collection<V> collection = getOrCreateCollection(key);
    Collection<V> oldValues = createCollection();
    oldValues.addAll(collection);

    totalSize -= collection.size();
    collection.clear();

    while (iterator.hasNext()) {
      if (collection.add(iterator.next())) {
        totalSize++;
      }
    }

    return unmodifiableCollectionSubclass(oldValues);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The returned collection is immutable.
   */
  @Override
  public Collection<V> removeAll(@Nullable Object key) {
    Collection<V> collection = map.remove(key);

    if (collection == null) {
      return createUnmodifiableEmptyCollection();
    }

    Collection<V> output = createCollection();
    output.addAll(collection);
    totalSize -= collection.size();
    collection.clear();

    return unmodifiableCollectionSubclass(output);
  }

  Collection<V> unmodifiableCollectionSubclass(Collection<V> collection) {
    // We don't deal with NavigableSet here yet for GWT reasons -- instead,
    // non-GWT TreeMultimap explicitly overrides this and uses NavigableSet.
    if (collection instanceof SortedSet) {
      return Collections.unmodifiableSortedSet((SortedSet<V>) collection);
    } else if (collection instanceof Set) {
      return Collections.unmodifiableSet((Set<V>) collection);
    } else if (collection instanceof List) {
      return Collections.unmodifiableList((List<V>) collection);
    } else {
      return Collections.unmodifiableCollection(collection);
    }
  }

  @Override
  public void clear() {
    // Clear each collection, to make previously returned collections empty.
    for (Collection<V> collection : map.values()) {
      collection.clear();
    }
    map.clear();
    totalSize = 0;
  }

  // Views

  /**
   * {@inheritDoc}
   *
   * <p>The returned collection is not serializable.
   */
  @Override
  public Collection<V> get(@Nullable K key) {
    Collection<V> collection = map.get(key);
    if (collection == null) {
      collection = createCollection(key);
    }
    return wrapCollection(key, collection);
  }

  /**
   * Generates a decorated collection that remains consistent with the values in
   * the multimap for the provided key. Changes to the multimap may alter the
   * returned collection, and vice versa.
   */
  Collection<V> wrapCollection(@Nullable K key, Collection<V> collection) {
    // We don't deal with NavigableSet here yet for GWT reasons -- instead,
    // non-GWT TreeMultimap explicitly overrides this and uses NavigableSet.
    if (collection instanceof SortedSet) {
      return new WrappedSortedSet(key, (SortedSet<V>) collection, null);
    } else if (collection instanceof Set) {
      return new WrappedSet(key, (Set<V>) collection);
    } else if (collection instanceof List) {
      return wrapList(key, (List<V>) collection, null);
    } else {
      return new WrappedCollection(key, collection, null);
    }
  }

  private List<V> wrapList(
      @Nullable K key, List<V> list, @Nullable WrappedCollection ancestor) {
    return (list instanceof RandomAccess)
        ? new RandomAccessWrappedList(key, list, ancestor)
        : new WrappedList(key, list, ancestor);
  }

  /**
   * Collection decorator that stays in sync with the multimap values for a key.
   * There are two kinds of wrapped collections: full and subcollections. Both
   * have a delegate pointing to the underlying collection class.
   *
   * <p>Full collections, identified by a null ancestor field, contain all
   * multimap values for a given key. Its delegate is a value in {@link
   * AbstractMapBasedMultimap#map} whenever the delegate is non-empty. The {@code
   * refreshIfEmpty}, {@code removeIfEmpty}, and {@code addToMap} methods ensure
   * that the {@code WrappedCollection} and map remain consistent.
   *
   * <p>A subcollection, such as a sublist, contains some of the values for a
   * given key. Its ancestor field points to the full wrapped collection with
   * all values for the key. The subcollection {@code refreshIfEmpty}, {@code
   * removeIfEmpty}, and {@code addToMap} methods call the corresponding methods
   * of the full wrapped collection.
   */
  private class WrappedCollection extends AbstractCollection<V> {
    final K key;
    Collection<V> delegate;
    final WrappedCollection ancestor;
    final Collection<V> ancestorDelegate;

    WrappedCollection(@Nullable K key, Collection<V> delegate,
        @Nullable WrappedCollection ancestor) {
      this.key = key;
      this.delegate = delegate;
      this.ancestor = ancestor;
      this.ancestorDelegate
          = (ancestor == null) ? null : ancestor.getDelegate();
    }

    /**
     * If the delegate collection is empty, but the multimap has values for the
     * key, replace the delegate with the new collection for the key.
     *
     * <p>For a subcollection, refresh its ancestor and validate that the
     * ancestor delegate hasn't changed.
     */
    void refreshIfEmpty() {
      if (ancestor != null) {
        ancestor.refreshIfEmpty();
        if (ancestor.getDelegate() != ancestorDelegate) {
          throw new ConcurrentModificationException();
        }
      } else if (delegate.isEmpty()) {
        Collection<V> newDelegate = map.get(key);
        if (newDelegate != null) {
          delegate = newDelegate;
        }
      }
    }

    /**
     * If collection is empty, remove it from {@code AbstractMapBasedMultimap.this.map}.
     * For subcollections, check whether the ancestor collection is empty.
     */
    void removeIfEmpty() {
      if (ancestor != null) {
        ancestor.removeIfEmpty();
      } else if (delegate.isEmpty()) {
        map.remove(key);
      }
    }

    K getKey() {
      return key;
    }

    /**
     * Add the delegate to the map. Other {@code WrappedCollection} methods
     * should call this method after adding elements to a previously empty
     * collection.
     *
     * <p>Subcollection add the ancestor's delegate instead.
     */
    void addToMap() {
      if (ancestor != null) {
        ancestor.addToMap();
      } else {
        map.put(key, delegate);
      }
    }

    @Override public int size() {
      refreshIfEmpty();
      return delegate.size();
    }

    @Override public boolean equals(@Nullable Object object) {
      if (object == this) {
        return true;
      }
      refreshIfEmpty();
      return delegate.equals(object);
    }

    @Override public int hashCode() {
      refreshIfEmpty();
      return delegate.hashCode();
    }

    @Override public String toString() {
      refreshIfEmpty();
      return delegate.toString();
    }

    Collection<V> getDelegate() {
      return delegate;
    }

    @Override public Iterator<V> iterator() {
      refreshIfEmpty();
      return new WrappedIterator();
    }

    /** Collection iterator for {@code WrappedCollection}. */
    class WrappedIterator implements Iterator<V> {
      final Iterator<V> delegateIterator;
      final Collection<V> originalDelegate = delegate;

      WrappedIterator() {
        delegateIterator = iteratorOrListIterator(delegate);
      }

      WrappedIterator(Iterator<V> delegateIterator) {
        this.delegateIterator = delegateIterator;
      }

      /**
       * If the delegate changed since the iterator was created, the iterator is
       * no longer valid.
       */
      void validateIterator() {
        refreshIfEmpty();
        if (delegate != originalDelegate) {
          throw new ConcurrentModificationException();
        }
      }

      @Override
      public boolean hasNext() {
        validateIterator();
        return delegateIterator.hasNext();
      }

      @Override
      public V next() {
        validateIterator();
        return delegateIterator.next();
      }

      @Override
      public void remove() {
        delegateIterator.remove();
        totalSize--;
        removeIfEmpty();
      }

      Iterator<V> getDelegateIterator() {
        validateIterator();
        return delegateIterator;
      }
    }

    @Override public boolean add(V value) {
      refreshIfEmpty();
      boolean wasEmpty = delegate.isEmpty();
      boolean changed = delegate.add(value);
      if (changed) {
        totalSize++;
        if (wasEmpty) {
          addToMap();
        }
      }
      return changed;
    }

    WrappedCollection getAncestor() {
      return ancestor;
    }

    // The following methods are provided for better performance.

    @Override public boolean addAll(Collection<? extends V> collection) {
      if (collection.isEmpty()) {
        return false;
      }
      int oldSize = size();  // calls refreshIfEmpty
      boolean changed = delegate.addAll(collection);
      if (changed) {
        int newSize = delegate.size();
        totalSize += (newSize - oldSize);
        if (oldSize == 0) {
          addToMap();
        }
      }
      return changed;
    }

    @Override public boolean contains(Object o) {
      refreshIfEmpty();
      return delegate.contains(o);
    }

    @Override public boolean containsAll(Collection<?> c) {
      refreshIfEmpty();
      return delegate.containsAll(c);
    }

    @Override public void clear() {
      int oldSize = size();  // calls refreshIfEmpty
      if (oldSize == 0) {
        return;
      }
      delegate.clear();
      totalSize -= oldSize;
      removeIfEmpty();       // maybe shouldn't be removed if this is a sublist
    }

    @Override public boolean remove(Object o) {
      refreshIfEmpty();
      boolean changed = delegate.remove(o);
      if (changed) {
        totalSize--;
        removeIfEmpty();
      }
      return changed;
    }

    @Override public boolean removeAll(Collection<?> c) {
      if (c.isEmpty()) {
        return false;
      }
      int oldSize = size();  // calls refreshIfEmpty
      boolean changed = delegate.removeAll(c);
      if (changed) {
        int newSize = delegate.size();
        totalSize += (newSize - oldSize);
        removeIfEmpty();
      }
      return changed;
    }

    @Override public boolean retainAll(Collection<?> c) {
      checkNotNull(c);
      int oldSize = size();  // calls refreshIfEmpty
      boolean changed = delegate.retainAll(c);
      if (changed) {
        int newSize = delegate.size();
        totalSize += (newSize - oldSize);
        removeIfEmpty();
      }
      return changed;
    }
  }

  private Iterator<V> iteratorOrListIterator(Collection<V> collection) {
    return (collection instanceof List)
        ? ((List<V>) collection).listIterator()
        : collection.iterator();
  }

  /** Set decorator that stays in sync with the multimap values for a key. */
  private class WrappedSet extends WrappedCollection implements Set<V> {
    WrappedSet(@Nullable K key, Set<V> delegate) {
      super(key, delegate, null);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
      if (c.isEmpty()) {
        return false;
      }
      int oldSize = size();  // calls refreshIfEmpty

      // Guava issue 1013: AbstractSet and most JDK set implementations are
      // susceptible to quadratic removeAll performance on lists;
      // use a slightly smarter implementation here
      boolean changed = Sets.removeAllImpl((Set<V>) delegate, c);
      if (changed) {
        int newSize = delegate.size();
        totalSize += (newSize - oldSize);
        removeIfEmpty();
      }
      return changed;
    }
  }

  /**
   * SortedSet decorator that stays in sync with the multimap values for a key.
   */
  private class WrappedSortedSet extends WrappedCollection
      implements SortedSet<V> {
    WrappedSortedSet(@Nullable K key, SortedSet<V> delegate,
        @Nullable WrappedCollection ancestor) {
      super(key, delegate, ancestor);
    }

    SortedSet<V> getSortedSetDelegate() {
      return (SortedSet<V>) getDelegate();
    }

    @Override
    public Comparator<? super V> comparator() {
      return getSortedSetDelegate().comparator();
    }

    @Override
    public V first() {
      refreshIfEmpty();
      return getSortedSetDelegate().first();
    }

    @Override
    public V last() {
      refreshIfEmpty();
      return getSortedSetDelegate().last();
    }

    @Override
    public SortedSet<V> headSet(V toElement) {
      refreshIfEmpty();
      return new WrappedSortedSet(
          getKey(), getSortedSetDelegate().headSet(toElement),
          (getAncestor() == null) ? this : getAncestor());
    }

    @Override
    public SortedSet<V> subSet(V fromElement, V toElement) {
      refreshIfEmpty();
      return new WrappedSortedSet(
          getKey(), getSortedSetDelegate().subSet(fromElement, toElement),
          (getAncestor() == null) ? this : getAncestor());
    }

    @Override
    public SortedSet<V> tailSet(V fromElement) {
      refreshIfEmpty();
      return new WrappedSortedSet(
          getKey(), getSortedSetDelegate().tailSet(fromElement),
          (getAncestor() == null) ? this : getAncestor());
    }
  }

  /** List decorator that stays in sync with the multimap values for a key. */
  private class WrappedList extends WrappedCollection implements List<V> {
    WrappedList(@Nullable K key, List<V> delegate,
        @Nullable WrappedCollection ancestor) {
      super(key, delegate, ancestor);
    }

    List<V> getListDelegate() {
      return (List<V>) getDelegate();
    }

    @Override
    public boolean addAll(int index, Collection<? extends V> c) {
      if (c.isEmpty()) {
        return false;
      }
      int oldSize = size();  // calls refreshIfEmpty
      boolean changed = getListDelegate().addAll(index, c);
      if (changed) {
        int newSize = getDelegate().size();
        totalSize += (newSize - oldSize);
        if (oldSize == 0) {
          addToMap();
        }
      }
      return changed;
    }

    @Override
    public V get(int index) {
      refreshIfEmpty();
      return getListDelegate().get(index);
    }

    @Override
    public V set(int index, V element) {
      refreshIfEmpty();
      return getListDelegate().set(index, element);
    }

    @Override
    public void add(int index, V element) {
      refreshIfEmpty();
      boolean wasEmpty = getDelegate().isEmpty();
      getListDelegate().add(index, element);
      totalSize++;
      if (wasEmpty) {
        addToMap();
      }
    }

    @Override
    public V remove(int index) {
      refreshIfEmpty();
      V value = getListDelegate().remove(index);
      totalSize--;
      removeIfEmpty();
      return value;
    }

    @Override
    public int indexOf(Object o) {
      refreshIfEmpty();
      return getListDelegate().indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
      refreshIfEmpty();
      return getListDelegate().lastIndexOf(o);
    }

    @Override
    public ListIterator<V> listIterator() {
      refreshIfEmpty();
      return new WrappedListIterator();
    }

    @Override
    public ListIterator<V> listIterator(int index) {
      refreshIfEmpty();
      return new WrappedListIterator(index);
    }

    @Override
    public List<V> subList(int fromIndex, int toIndex) {
      refreshIfEmpty();
      return wrapList(getKey(),
          getListDelegate().subList(fromIndex, toIndex),
          (getAncestor() == null) ? this : getAncestor());
    }

    /** ListIterator decorator. */
    private class WrappedListIterator extends WrappedIterator
        implements ListIterator<V> {
      WrappedListIterator() {}

      public WrappedListIterator(int index) {
        super(getListDelegate().listIterator(index));
      }

      private ListIterator<V> getDelegateListIterator() {
        return (ListIterator<V>) getDelegateIterator();
      }

      @Override
      public boolean hasPrevious() {
        return getDelegateListIterator().hasPrevious();
      }

      @Override
      public V previous() {
        return getDelegateListIterator().previous();
      }

      @Override
      public int nextIndex() {
        return getDelegateListIterator().nextIndex();
      }

      @Override
      public int previousIndex() {
        return getDelegateListIterator().previousIndex();
      }

      @Override
      public void set(V value) {
        getDelegateListIterator().set(value);
      }

      @Override
      public void add(V value) {
        boolean wasEmpty = isEmpty();
        getDelegateListIterator().add(value);
        totalSize++;
        if (wasEmpty) {
          addToMap();
        }
      }
    }
  }

  /**
   * List decorator that stays in sync with the multimap values for a key and
   * supports rapid random access.
   */
  private class RandomAccessWrappedList extends WrappedList
      implements RandomAccess {
    RandomAccessWrappedList(@Nullable K key, List<V> delegate,
        @Nullable WrappedCollection ancestor) {
      super(key, delegate, ancestor);
    }
  }

  @Override
  Set<K> createKeySet() {
    // TreeMultimap uses NavigableKeySet explicitly, but we don't handle that here for GWT
    // compatibility reasons
    return (map instanceof SortedMap)
        ? new SortedKeySet((SortedMap<K, Collection<V>>) map) : new KeySet(map);
  }

  private class KeySet extends Maps.KeySet<K, Collection<V>> {
    KeySet(final Map<K, Collection<V>> subMap) {
      super(subMap);
    }

    @Override public Iterator<K> iterator() {
      final Iterator<Map.Entry<K, Collection<V>>> entryIterator
          = map().entrySet().iterator();
      return new Iterator<K>() {
        Map.Entry<K, Collection<V>> entry;

        @Override
        public boolean hasNext() {
          return entryIterator.hasNext();
        }
        @Override
        public K next() {
          entry = entryIterator.next();
          return entry.getKey();
        }
        @Override
        public void remove() {
          checkRemove(entry != null);
          Collection<V> collection = entry.getValue();
          entryIterator.remove();
          totalSize -= collection.size();
          collection.clear();
        }
      };
    }

    // The following methods are included for better performance.

    @Override public boolean remove(Object key) {
      int count = 0;
      Collection<V> collection = map().remove(key);
      if (collection != null) {
        count = collection.size();
        collection.clear();
        totalSize -= count;
      }
      return count > 0;
    }

    @Override
    public void clear() {
      Iterators.clear(iterator());
    }

    @Override public boolean containsAll(Collection<?> c) {
      return map().keySet().containsAll(c);
    }

    @Override public boolean equals(@Nullable Object object) {
      return this == object || this.map().keySet().equals(object);
    }

    @Override public int hashCode() {
      return map().keySet().hashCode();
    }
  }

  private class SortedKeySet extends KeySet implements SortedSet<K> {

    SortedKeySet(SortedMap<K, Collection<V>> subMap) {
      super(subMap);
    }

    SortedMap<K, Collection<V>> sortedMap() {
      return (SortedMap<K, Collection<V>>) super.map();
    }

    @Override
    public Comparator<? super K> comparator() {
      return sortedMap().comparator();
    }

    @Override
    public K first() {
      return sortedMap().firstKey();
    }

    @Override
    public SortedSet<K> headSet(K toElement) {
      return new SortedKeySet(sortedMap().headMap(toElement));
    }

    @Override
    public K last() {
      return sortedMap().lastKey();
    }

    @Override
    public SortedSet<K> subSet(K fromElement, K toElement) {
      return new SortedKeySet(sortedMap().subMap(fromElement, toElement));
    }

    @Override
    public SortedSet<K> tailSet(K fromElement) {
      return new SortedKeySet(sortedMap().tailMap(fromElement));
    }
  }

  /**
   * Removes all values for the provided key. Unlike {@link #removeAll}, it
   * returns the number of removed mappings.
   */
  private int removeValuesForKey(Object key) {
    Collection<V> collection = Maps.safeRemove(map, key);

    int count = 0;
    if (collection != null) {
      count = collection.size();
      collection.clear();
      totalSize -= count;
    }
    return count;
  }

  private abstract class Itr<T> implements Iterator<T> {
    final Iterator<Map.Entry<K, Collection<V>>> keyIterator;
    K key;
    Collection<V> collection;
    Iterator<V> valueIterator;

    Itr() {
      keyIterator = map.entrySet().iterator();
      key = null;
      collection = null;
      valueIterator = Iterators.emptyModifiableIterator();
    }

    abstract T output(K key, V value);

    @Override
    public boolean hasNext() {
      return keyIterator.hasNext() || valueIterator.hasNext();
    }

    @Override
    public T next() {
      if (!valueIterator.hasNext()) {
        Map.Entry<K, Collection<V>> mapEntry = keyIterator.next();
        key = mapEntry.getKey();
        collection = mapEntry.getValue();
        valueIterator = collection.iterator();
      }
      return output(key, valueIterator.next());
    }

    @Override
    public void remove() {
      valueIterator.remove();
      if (collection.isEmpty()) {
        keyIterator.remove();
      }
      totalSize--;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>The iterator generated by the returned collection traverses the values
   * for one key, followed by the values of a second key, and so on.
   */
  @Override public Collection<V> values() {
    return super.values();
  }

  @Override
  Iterator<V> valueIterator() {
    return new Itr<V>() {
      @Override
      V output(K key, V value) {
        return value;
      }
    };
  }

  /*
   * TODO(kevinb): should we copy this javadoc to each concrete class, so that
   * classes like LinkedHashMultimap that need to say something different are
   * still able to {@inheritDoc} all the way from Multimap?
   */

  /**
   * {@inheritDoc}
   *
   * <p>The iterator generated by the returned collection traverses the values
   * for one key, followed by the values of a second key, and so on.
   *
   * <p>Each entry is an immutable snapshot of a key-value mapping in the
   * multimap, taken at the time the entry is returned by a method call to the
   * collection or its iterator.
   */
  @Override
  public Collection<Map.Entry<K, V>> entries() {
    return super.entries();
  }

  /**
   * Returns an iterator across all key-value map entries, used by {@code
   * entries().iterator()} and {@code values().iterator()}. The default
   * behavior, which traverses the values for one key, the values for a second
   * key, and so on, suffices for most {@code AbstractMapBasedMultimap} implementations.
   *
   * @return an iterator across map entries
   */
  @Override
  Iterator<Map.Entry<K, V>> entryIterator() {
    return new Itr<Map.Entry<K, V>>() {
      @Override
      Entry<K, V> output(K key, V value) {
        return Maps.immutableEntry(key, value);
      }
    };
  }

  @Override
  Map<K, Collection<V>> createAsMap() {
    // TreeMultimap uses NavigableAsMap explicitly, but we don't handle that here for GWT
    // compatibility reasons
    return (map instanceof SortedMap)
        ? new SortedAsMap((SortedMap<K, Collection<V>>) map) : new AsMap(map);
  }

  private class AsMap extends ImprovedAbstractMap<K, Collection<V>> {
    /**
     * Usually the same as map, but smaller for the headMap(), tailMap(), or
     * subMap() of a SortedAsMap.
     */
    final transient Map<K, Collection<V>> submap;

    AsMap(Map<K, Collection<V>> submap) {
      this.submap = submap;
    }

    @Override
    protected Set<Entry<K, Collection<V>>> createEntrySet() {
      return new AsMapEntries();
    }

    // The following methods are included for performance.

    @Override public boolean containsKey(Object key) {
      return Maps.safeContainsKey(submap, key);
    }

    @Override public Collection<V> get(Object key) {
      Collection<V> collection = Maps.safeGet(submap, key);
      if (collection == null) {
        return null;
      }
      @SuppressWarnings("unchecked")
      K k = (K) key;
      return wrapCollection(k, collection);
    }

    @Override public Set<K> keySet() {
      return AbstractMapBasedMultimap.this.keySet();
    }

    @Override
    public int size() {
      return submap.size();
    }

    @Override public Collection<V> remove(Object key) {
      Collection<V> collection = submap.remove(key);
      if (collection == null) {
        return null;
      }

      Collection<V> output = createCollection();
      output.addAll(collection);
      totalSize -= collection.size();
      collection.clear();
      return output;
    }

    @Override public boolean equals(@Nullable Object object) {
      return this == object || submap.equals(object);
    }

    @Override public int hashCode() {
      return submap.hashCode();
    }

    @Override public String toString() {
      return submap.toString();
    }

    @Override
    public void clear() {
      if (submap == map) {
        AbstractMapBasedMultimap.this.clear();
      } else {

        Iterators.clear(new AsMapIterator());
      }
    }

    Entry<K, Collection<V>> wrapEntry(Entry<K, Collection<V>> entry) {
      K key = entry.getKey();
      return Maps.immutableEntry(key, wrapCollection(key, entry.getValue()));
    }

    class AsMapEntries extends Maps.EntrySet<K, Collection<V>> {
      @Override
      Map<K, Collection<V>> map() {
        return AsMap.this;
      }

      @Override public Iterator<Map.Entry<K, Collection<V>>> iterator() {
        return new AsMapIterator();
      }

      // The following methods are included for performance.

      @Override public boolean contains(Object o) {
        return Collections2.safeContains(submap.entrySet(), o);
      }

      @Override public boolean remove(Object o) {
        if (!contains(o)) {
          return false;
        }
        Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
        removeValuesForKey(entry.getKey());
        return true;
      }
    }

    /** Iterator across all keys and value collections. */
    class AsMapIterator implements Iterator<Map.Entry<K, Collection<V>>> {
      final Iterator<Map.Entry<K, Collection<V>>> delegateIterator
          = submap.entrySet().iterator();
      Collection<V> collection;

      @Override
      public boolean hasNext() {
        return delegateIterator.hasNext();
      }

      @Override
      public Map.Entry<K, Collection<V>> next() {
        Map.Entry<K, Collection<V>> entry = delegateIterator.next();
        collection = entry.getValue();
        return wrapEntry(entry);
      }

      @Override
      public void remove() {
        delegateIterator.remove();
        totalSize -= collection.size();
        collection.clear();
      }
    }
  }

  private class SortedAsMap extends AsMap
      implements SortedMap<K, Collection<V>> {
    SortedAsMap(SortedMap<K, Collection<V>> submap) {
      super(submap);
    }

    SortedMap<K, Collection<V>> sortedMap() {
      return (SortedMap<K, Collection<V>>) submap;
    }

    @Override
    public Comparator<? super K> comparator() {
      return sortedMap().comparator();
    }

    @Override
    public K firstKey() {
      return sortedMap().firstKey();
    }

    @Override
    public K lastKey() {
      return sortedMap().lastKey();
    }

    @Override
    public SortedMap<K, Collection<V>> headMap(K toKey) {
      return new SortedAsMap(sortedMap().headMap(toKey));
    }

    @Override
    public SortedMap<K, Collection<V>> subMap(K fromKey, K toKey) {
      return new SortedAsMap(sortedMap().subMap(fromKey, toKey));
    }

    @Override
    public SortedMap<K, Collection<V>> tailMap(K fromKey) {
      return new SortedAsMap(sortedMap().tailMap(fromKey));
    }

    SortedSet<K> sortedKeySet;

    // returns a SortedSet, even though returning a Set would be sufficient to
    // satisfy the SortedMap.keySet() interface
    @Override public SortedSet<K> keySet() {
      SortedSet<K> result = sortedKeySet;
      return (result == null) ? sortedKeySet = createKeySet() : result;
    }

    @Override
    SortedSet<K> createKeySet() {
      return new SortedKeySet(sortedMap());
    }
  }

  private static final long serialVersionUID = 2447537837011683357L;
}
