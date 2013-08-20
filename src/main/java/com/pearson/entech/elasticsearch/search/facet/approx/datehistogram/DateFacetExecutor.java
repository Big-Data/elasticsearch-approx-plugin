package com.pearson.entech.elasticsearch.search.facet.approx.datehistogram;

import java.io.IOException;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.joda.TimeZoneRounding;
import org.elasticsearch.common.trove.ExtTHashMap;
import org.elasticsearch.common.trove.ExtTLongObjectHashMap;
import org.elasticsearch.common.trove.map.TLongObjectMap;
import org.elasticsearch.common.trove.map.hash.TLongIntHashMap;
import org.elasticsearch.common.trove.map.hash.TObjectIntHashMap;
import org.elasticsearch.index.fielddata.BytesValues;
import org.elasticsearch.index.fielddata.LongValues;
import org.elasticsearch.index.fielddata.LongValues.Iter;
import org.elasticsearch.index.fielddata.plain.LongArrayIndexFieldData;
import org.elasticsearch.search.facet.FacetExecutor;
import org.elasticsearch.search.facet.InternalFacet;

public class DateFacetExecutor extends FacetExecutor {

    private final TypedFieldData _keyFieldData;
    private final TypedFieldData _valueFieldData;
    private final TypedFieldData _distinctFieldData;
    private final TypedFieldData _sliceFieldData;

    private final BuildableCollector _collector;

    private final TimeZoneRounding _tzRounding;
    private final int _exactThreshold;

    public DateFacetExecutor(final TypedFieldData keyFieldData, final TypedFieldData valueFieldData,
            final TypedFieldData distinctFieldData, final TypedFieldData sliceFieldData,
            final TimeZoneRounding tzRounding, final int exactThreshold) {
        _keyFieldData = keyFieldData;
        _valueFieldData = valueFieldData;
        _distinctFieldData = distinctFieldData;
        _sliceFieldData = sliceFieldData;
        _tzRounding = tzRounding;
        _exactThreshold = exactThreshold;
        if(_distinctFieldData == null && _sliceFieldData == null)
            _collector = new CountingCollector();
        else if(_distinctFieldData == null)
            _collector = new SlicedCollector();
        else if(_sliceFieldData == null)
            _collector = new DistinctCollector();
        else
            _collector = new SlicedDistinctCollector();
    }

    @Override
    public InternalFacet buildFacet(final String facetName) {
        return _collector.build(facetName);
    }

    @Override
    public Collector collector() {
        return _collector;
    }

    // TODO better checking for 0-length collections and other trip-ups
    // TODO sorting of data within facets
    // TODO tests for other facets, not just distinct
    // TODO keep track of totals and missing values
    // TODO replace "new DistinctCountPayload()" with an object cache
    // TODO global cache of the counts from each collector
    // TODO cache tz calculations
    // TODO limits on terms used in slicing (min freq/top N)
    // TODO make interval optional, so we can just have one bucket (custom TimeZoneRounding)
    // TODO stop using long arrays as wrappers for counters (materialize methods)
    // TODO support other slice labels apart from String?
    // TODO replace NullEntry with a mixin for having an entry, maybe
    // TODO surface the slice field and the distinct field name in the results
    // TODO exclude deserialized and other "foreign" objects from CacheRecycler
    // TODO better Java API (don't use internal classes)
    // TODO make these collectors static classes, or break them out (to avoid ref. to executor)
    // TODO wrappers around iterators so we can get bytes for numeric fields without converting to strings first

    private class CountingCollector extends BuildableCollector {

        private LongValues _keyFieldValues;
        private BytesValues _valueFieldValues;

        private TLongIntHashMap _counts;

        CountingCollector() {
            _counts = CacheRecycler.popLongIntMap();
        }

        @Override
        public void setNextReader(final AtomicReaderContext context) throws IOException {
            _keyFieldValues = ((LongArrayIndexFieldData) _keyFieldData.data)
                    .load(context).getLongValues();
            if(_valueFieldData != null)
                // TODO if these aren't strings, this isn't the most efficient way:
                _valueFieldValues = _valueFieldData.data.load(context).getBytesValues();
        }

        @Override
        public void collect(final int doc) throws IOException {
            final Iter keyIter = _keyFieldValues.getIter(doc);

            if(_valueFieldData == null) {
                // We are only counting docs
                while(keyIter.hasNext()) {
                    final long time = _tzRounding.calc(keyIter.next());
                    _counts.adjustOrPutValue(time, 1, 1);
                }
            } else {
                while(keyIter.hasNext()) {
                    // We are counting each occurrence of valueField (regardless of its contents)
                    final org.elasticsearch.index.fielddata.BytesValues.Iter valIter =
                            _valueFieldValues.getIter(doc);
                    if(!valIter.hasNext())
                        return;

                    final long time = _tzRounding.calc(keyIter.next());
                    while(valIter.hasNext()) {
                        valIter.next();
                        _counts.adjustOrPutValue(time, 1, 1);
                    }
                }
            }
        }

        @Override
        public void postCollection() {}

        @Override
        public InternalFacet build(final String facetName) {
            final InternalFacet facet = new InternalCountingFacet(facetName, _counts);
            _keyFieldValues = null;
            _counts = null;
            return facet;
        }

    }

    private class SlicedCollector extends BuildableCollector {

        private LongValues _keyFieldValues;
        private BytesValues _sliceFieldValues;
        private BytesValues _valueFieldValues;

        private ExtTLongObjectHashMap<TObjectIntHashMap<BytesRef>> _counts;

        SlicedCollector() {
            _counts = CacheRecycler.popLongObjectMap();
        }

        @Override
        public void setNextReader(final AtomicReaderContext context) throws IOException {
            _keyFieldValues = ((LongArrayIndexFieldData) _keyFieldData.data)
                    .load(context).getLongValues();
            // TODO if these aren't strings, this isn't the most efficient way:
            _sliceFieldValues = _sliceFieldData.data.load(context).getBytesValues();
            if(_valueFieldData != null)
                // TODO if these aren't strings, this isn't the most efficient way:
                _valueFieldValues = _valueFieldData.data.load(context).getBytesValues();
        }

        @Override
        public void collect(final int doc) throws IOException {
            // Exit as early as possible in order to avoid unnecessary lookups

            final Iter keyIter = _keyFieldValues.getIter(doc);
            if(!keyIter.hasNext())
                return;

            if(_valueFieldData == null) {
                // We are only counting docs for each slice
                while(keyIter.hasNext()) {
                    final org.elasticsearch.index.fielddata.BytesValues.Iter sliceIter =
                            _sliceFieldValues.getIter(doc);
                    if(!sliceIter.hasNext())
                        return;

                    final long time = _tzRounding.calc(keyIter.next());

                    while(sliceIter.hasNext()) {
                        // TODO we can reduce hash lookups by getting the outer map in the outer loop
                        incrementSafely(_counts, time, sliceIter.next());
                    }
                }
            } else {
                // We are counting each occurrence of value_field in each slice (regardless of its contents)
                while(keyIter.hasNext()) {
                    final org.elasticsearch.index.fielddata.BytesValues.Iter sliceIter =
                            _sliceFieldValues.getIter(doc);
                    if(!sliceIter.hasNext())
                        return;

                    final long time = _tzRounding.calc(keyIter.next());

                    while(sliceIter.hasNext()) {
                        final org.elasticsearch.index.fielddata.BytesValues.Iter valIter =
                                _valueFieldValues.getIter(doc);
                        while(valIter.hasNext()) {
                            // TODO we can reduce hash lookups by getting the outer map in the outer loop
                            final BytesRef unsafe = sliceIter.next();
                            incrementSafely(_counts, time, unsafe);
                        }
                    }
                }
            }

        }

        @Override
        public void postCollection() {}

        @Override
        public InternalFacet build(final String facetName) {
            final InternalFacet facet = new InternalSlicedFacet(facetName, _counts);
            _keyFieldValues = null;
            _sliceFieldValues = null;
            _counts = null;
            return facet;
        }

        private void incrementSafely(final TLongObjectMap<TObjectIntHashMap<BytesRef>> counts,
                final long key, final BytesRef unsafe) {
            TObjectIntHashMap<BytesRef> subMap = counts.get(key);
            if(subMap == null) {
                subMap = CacheRecycler.popObjectIntMap();
                counts.put(key, subMap);
            }
            final BytesRef safe = BytesRef.deepCopyOf(unsafe);
            subMap.adjustOrPutValue(safe, 1, 1);
        }

    }

    private class DistinctCollector extends BuildableCollector {

        private LongValues _keyFieldValues;
        private BytesValues _distinctFieldValues;

        private final ExtTLongObjectHashMap<DistinctCountPayload> _counts;

        DistinctCollector() {
            _counts = CacheRecycler.popLongObjectMap();
        }

        @Override
        public void setNextReader(final AtomicReaderContext context) throws IOException {
            _keyFieldValues = ((LongArrayIndexFieldData) _keyFieldData.data)
                    .load(context).getLongValues();
            // TODO if these aren't strings, this isn't the most efficient way:
            _distinctFieldValues = _distinctFieldData.data.load(context).getBytesValues();
        }

        @Override
        public void collect(final int doc) throws IOException {
            // Exit as early as possible in order to avoid unnecessary lookups

            final Iter keyIter = _keyFieldValues.getIter(doc);
            if(!keyIter.hasNext())
                return;

            final org.elasticsearch.index.fielddata.BytesValues.Iter distinctIter =
                    _distinctFieldValues.getIter(doc);
            if(!distinctIter.hasNext())
                return;

            while(keyIter.hasNext()) {
                final long time = _tzRounding.calc(keyIter.next());
                final DistinctCountPayload count = getSafely(_counts, time);
                while(distinctIter.hasNext()) {
                    // NB this causes two conversions if the field's numeric
                    final BytesRef unsafe = distinctIter.next();
                    // Unsafe because this may change; the counter needs to make
                    // it safe if it's going to keep hold of the bytes
                    count.update(unsafe);
                }
            }
        }

        private DistinctCountPayload getSafely(final TLongObjectMap<DistinctCountPayload> counts, final long key) {
            DistinctCountPayload payload = counts.get(key);
            if(payload == null) {
                payload = new DistinctCountPayload(_exactThreshold);
                counts.put(key, payload);
            }
            return payload;
        }

        @Override
        public void postCollection() {}

        @Override
        public InternalFacet build(final String facetName) {
            final InternalFacet facet = new InternalDistinctFacet(facetName, _counts);
            _keyFieldValues = null;
            _distinctFieldValues = null;
            return facet;
        }

    }

    private class SlicedDistinctCollector extends BuildableCollector {

        private LongValues _keyFieldValues;
        private BytesValues _distinctFieldValues;
        private BytesValues _sliceFieldValues;

        private final ExtTLongObjectHashMap<ExtTHashMap<BytesRef, DistinctCountPayload>> _counts;

        SlicedDistinctCollector() {
            _counts = CacheRecycler.popLongObjectMap();
        }

        @Override
        public void setNextReader(final AtomicReaderContext context) throws IOException {
            _keyFieldValues = ((LongArrayIndexFieldData) _keyFieldData.data)
                    .load(context).getLongValues();
            // TODO if these aren't strings, this isn't the most efficient way:
            _distinctFieldValues = _distinctFieldData.data.load(context).getBytesValues();
            _sliceFieldValues = _sliceFieldData.data.load(context).getBytesValues();
        }

        @Override
        public void collect(final int doc) throws IOException {
            final Iter keyIter = _keyFieldValues.getIter(doc);
            final org.elasticsearch.index.fielddata.BytesValues.Iter distinctIter =
                    _distinctFieldValues.getIter(doc);
            final org.elasticsearch.index.fielddata.BytesValues.Iter sliceIter =
                    _sliceFieldValues.getIter(doc);
            while(keyIter.hasNext()) {
                final long time = _tzRounding.calc(keyIter.next());
                while(sliceIter.hasNext()) {
                    // TODO we can reduce hash lookups by getting the outer map in the outer loop
                    final BytesRef unsafeSlice = sliceIter.next();
                    final DistinctCountPayload count = getSafely(_counts, time, unsafeSlice);
                    while(distinctIter.hasNext()) {
                        final BytesRef unsafeTerm = distinctIter.next();
                        // Unsafe because this may change; the counter needs to make
                        // it safe if it's going to keep hold of the bytes
                        count.update(unsafeTerm);
                    }
                }
            }
        }

        @Override
        public void postCollection() {}

        private DistinctCountPayload getSafely(
                final TLongObjectMap<ExtTHashMap<BytesRef, DistinctCountPayload>> counts,
                final long key, final BytesRef unsafe) {
            ExtTHashMap<BytesRef, DistinctCountPayload> subMap = counts.get(key);
            if(subMap == null) {
                subMap = CacheRecycler.popHashMap();
                counts.put(key, subMap);
            }
            final BytesRef safe = BytesRef.deepCopyOf(unsafe);
            DistinctCountPayload payload = subMap.get(safe);
            if(payload == null) {
                payload = counts.get(key).put(safe, new DistinctCountPayload(_exactThreshold));
            }
            return payload;
        }

        @Override
        public InternalFacet build(final String facetName) {
            final InternalFacet facet = new InternalSlicedDistinctFacet(facetName, _counts);
            _keyFieldValues = null;
            _distinctFieldValues = null;
            _sliceFieldValues = null;
            return facet;
        }

    }

    private abstract class BuildableCollector extends Collector {

        abstract InternalFacet build(String facetName);

    }

}
