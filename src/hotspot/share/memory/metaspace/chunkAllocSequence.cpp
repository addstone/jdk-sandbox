/*
 * Copyright (c) 2019, SAP SE. All rights reserved.
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */
#include "precompiled.hpp"

#include "memory/metaspace/chunkAllocSequence.hpp"
#include "memory/metaspace/chunkLevel.hpp"
#include "utilities/globalDefinitions.hpp"

namespace metaspace {

// A chunk allocation sequence which can be encoded with a simple const array.
class ConstantChunkAllocSequence : public ChunkAllocSequence {

  // integer array specifying chunk level allocation progression.
  // Last chunk is to be an endlessly repeated allocation.
  const chklvl_t* const _entries;
  const int _num_entries;

public:

  ConstantChunkAllocSequence(const chklvl_t* array, int num_entries)
    : _entries(array)
    , _num_entries(num_entries)
  {
    assert(_num_entries > 0, "must not be empty.");
  }

  chklvl_t get_next_chunk_level(int num_allocated) const {
    if (num_allocated >= _num_entries) {
      // Caller shall repeat last allocation
      return _entries[_num_entries - 1];
    }
    return _entries[num_allocated];
  }

};

// hard-coded chunk allocation sequences for various space types

static const chklvl_t g_sequ_standard_non_class[] = {
    chklvl::CHUNK_LEVEL_4K,
    chklvl::CHUNK_LEVEL_4K,
    chklvl::CHUNK_LEVEL_4K,
    chklvl::CHUNK_LEVEL_4K,
    chklvl::CHUNK_LEVEL_16K
    // .. repeat last
};

static const chklvl_t g_sequ_standard_class[] = {
    chklvl::CHUNK_LEVEL_2K,
    chklvl::CHUNK_LEVEL_2K,
    chklvl::CHUNK_LEVEL_2K,
    chklvl::CHUNK_LEVEL_2K,
    chklvl::CHUNK_LEVEL_16K
    // .. repeat last
};

static const chklvl_t g_sequ_anon_non_class[] = {
    chklvl::CHUNK_LEVEL_1K,
    // .. repeat last
};

static const chklvl_t g_sequ_anon_class[] = {
    chklvl::CHUNK_LEVEL_1K,
    // .. repeat last
};

static const chklvl_t g_sequ_refl_non_class[] = {
    chklvl::CHUNK_LEVEL_2K,
    chklvl::CHUNK_LEVEL_1K
    // .. repeat last
};

static const chklvl_t g_sequ_refl_class[] = {
    chklvl::CHUNK_LEVEL_1K,
    // .. repeat last
};

// Boot class loader: give it large chunks: beyond commit granule size
// (typically 64K) the costs for large chunks largely diminishes since
// they are committed on the fly.
static const chklvl_t g_sequ_boot_non_class[] = {
    chklvl::CHUNK_LEVEL_4M,
    chklvl::CHUNK_LEVEL_1M
    // .. repeat last
};

static const chklvl_t g_sequ_boot_class[] = {
    chklvl::CHUNK_LEVEL_1M,
    chklvl::CHUNK_LEVEL_256K
    // .. repeat last
};

#define DEFINE_CLASS_FOR_ARRAY(what) \
  static ConstantChunkAllocSequence g_chunk_alloc_sequence_##what (g_sequ_##what, sizeof(g_sequ_##what)/sizeof(chklvl_t));

DEFINE_CLASS_FOR_ARRAY(standard_non_class)
DEFINE_CLASS_FOR_ARRAY(standard_class)
DEFINE_CLASS_FOR_ARRAY(anon_non_class)
DEFINE_CLASS_FOR_ARRAY(anon_class)
DEFINE_CLASS_FOR_ARRAY(refl_non_class)
DEFINE_CLASS_FOR_ARRAY(refl_class)
DEFINE_CLASS_FOR_ARRAY(boot_non_class)
DEFINE_CLASS_FOR_ARRAY(boot_class)

const ChunkAllocSequence* ChunkAllocSequence::alloc_sequence_by_space_type(MetaspaceType space_type, bool is_class) {

  if (is_class) {
    switch(space_type) {
    case StandardMetaspaceType:          return &g_chunk_alloc_sequence_standard_class;
    case ReflectionMetaspaceType:        return &g_chunk_alloc_sequence_refl_class;
    case ClassMirrorHolderMetaspaceType: return &g_chunk_alloc_sequence_anon_class;
    case BootMetaspaceType:              return &g_chunk_alloc_sequence_boot_non_class;
    default: ShouldNotReachHere();
    }
  } else {
    switch(space_type) {
    case StandardMetaspaceType:          return &g_chunk_alloc_sequence_standard_non_class;
    case ReflectionMetaspaceType:        return &g_chunk_alloc_sequence_refl_non_class;
    case ClassMirrorHolderMetaspaceType: return &g_chunk_alloc_sequence_anon_non_class;
    case BootMetaspaceType:              return &g_chunk_alloc_sequence_boot_non_class;
    default: ShouldNotReachHere();
    }
  }

  return NULL;

}



} // namespace metaspace

