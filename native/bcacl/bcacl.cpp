// bcacl: decompresses the ACL 2.1 buffers inside Bodycam's cooked AnimSequences, for BodycamCraft (loaded through JNA).
//
// A buffer starts at the 8-byte raw header (size, hash) in front of the 0xac11ac11 tag. Transform tracks come out as
// 10 floats per track per sample (rotation x y z w, translation x y z, scale x y z; NaN where the bind pose applies),
// scalar tracks as their component
// count per track per sample. Samples are taken at i / sample_rate, i = 0 .. num_samples - 1.
#include <acl/core/compressed_tracks.h>
#include <acl/decompression/decompress.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <malloc.h>

namespace
{
	// Accepts every format the compressor can write; Bodycam's settings are not assumed.
	struct scalar_settings : public acl::decompression_settings
	{
		static constexpr bool is_track_type_supported(acl::track_type8 type) { return type != acl::track_type8::qvvf; }
	};

	struct transform_writer final : public acl::track_writer
	{
		float* out;
		uint32_t num_tracks;
		uint32_t sample;

		// Unreal's ACL plugin leaves out sub-tracks equal to the bind pose; they stay NaN here and the caller fills
		// them from the skeleton's reference pose.
		static constexpr acl::default_sub_track_mode get_default_rotation_mode() { return acl::default_sub_track_mode::skipped; }
		static constexpr acl::default_sub_track_mode get_default_translation_mode() { return acl::default_sub_track_mode::skipped; }
		static constexpr acl::default_sub_track_mode get_default_scale_mode() { return acl::default_sub_track_mode::skipped; }

		float* at(uint32_t track) const { return out + (size_t(sample) * num_tracks + track) * 10; }
		void RTM_SIMD_CALL write_rotation(uint32_t track, rtm::quatf_arg0 q) { rtm::quat_store(q, at(track)); }
		void RTM_SIMD_CALL write_translation(uint32_t track, rtm::vector4f_arg0 v) { rtm::vector_store3(v, at(track) + 4); }
		void RTM_SIMD_CALL write_scale(uint32_t track, rtm::vector4f_arg0 v) { rtm::vector_store3(v, at(track) + 7); }
	};

	struct scalar_writer final : public acl::track_writer
	{
		float* out;
		uint32_t stride;
		uint32_t sample;

		float* at(uint32_t track, uint32_t width) const { return out + size_t(sample) * stride + size_t(track) * width; }
		void RTM_SIMD_CALL write_float1(uint32_t track, rtm::scalarf_arg0 v) { rtm::scalar_store(v, at(track, 1)); }
		void RTM_SIMD_CALL write_float2(uint32_t track, rtm::vector4f_arg0 v) { rtm::vector_store2(v, at(track, 2)); }
		void RTM_SIMD_CALL write_float3(uint32_t track, rtm::vector4f_arg0 v) { rtm::vector_store3(v, at(track, 3)); }
		void RTM_SIMD_CALL write_float4(uint32_t track, rtm::vector4f_arg0 v) { rtm::vector_store(v, at(track, 4)); }
		void RTM_SIMD_CALL write_vector4(uint32_t track, rtm::vector4f_arg0 v) { rtm::vector_store(v, at(track, 4)); }
	};

	uint32_t width_of(acl::track_type8 type)
	{
		switch (type)
		{
		case acl::track_type8::float1f: return 1;
		case acl::track_type8::float2f: return 2;
		case acl::track_type8::float3f: return 3;
		case acl::track_type8::float4f:
		case acl::track_type8::vector4f: return 4;
		case acl::track_type8::qvvf: return 10;
		default: return 0;
		}
	}

	// ACL wants the buffer 16-byte aligned; the bytes from the package are not.
	const acl::compressed_tracks* load(const void* buffer, size_t length, void** owned)
	{
		*owned = nullptr;
		if (buffer == nullptr || length < 32)
			return nullptr;
		void* copy = _aligned_malloc(length, 16);
		if (copy == nullptr)
			return nullptr;
		std::memcpy(copy, buffer, length);
		const acl::compressed_tracks* tracks = acl::make_compressed_tracks(copy);
		if (tracks == nullptr || tracks->get_size() > length || tracks->is_valid(true).any())
		{
			_aligned_free(copy);
			return nullptr;
		}
		*owned = copy;
		return tracks;
	}
}

extern "C"
{
	// info[0] tracks, info[1] samples, info[2] floats per track per sample, info[3] ACL version.
	// *rate gets the sample rate. Returns 0, or -1 when the buffer isn't valid ACL.
	__declspec(dllexport) int bcacl_info(const void* buffer, size_t length, uint32_t* info, float* rate)
	{
		void* owned;
		const acl::compressed_tracks* tracks = load(buffer, length, &owned);
		if (tracks == nullptr)
			return -1;
		info[0] = tracks->get_num_tracks();
		info[1] = tracks->get_num_samples_per_track();
		info[2] = width_of(tracks->get_track_type());
		info[3] = uint32_t(tracks->get_version());
		*rate = tracks->get_sample_rate();
		_aligned_free(owned);
		return info[2] == 0 ? -2 : 0;
	}

	// Writes tracks * samples * width floats to out. Returns the number written, or a negative error.
	__declspec(dllexport) int64_t bcacl_decode(const void* buffer, size_t length, float* out, size_t capacity)
	{
		void* owned;
		const acl::compressed_tracks* tracks = load(buffer, length, &owned);
		if (tracks == nullptr)
			return -1;
		const uint32_t num_tracks = tracks->get_num_tracks();
		const uint32_t num_samples = tracks->get_num_samples_per_track();
		const uint32_t width = width_of(tracks->get_track_type());
		const float rate = tracks->get_sample_rate();
		const size_t needed = size_t(num_tracks) * num_samples * width;
		int64_t result = -2;
		if (width == 0 || rate <= 0.0F)
			result = -2;
		else if (needed > capacity)
			result = -3;
		else if (tracks->get_track_type() == acl::track_type8::qvvf)
		{
			acl::decompression_context<acl::debug_transform_decompression_settings> context;
			if (context.initialize(*tracks))
			{
				for (size_t i = 0; i < needed; ++i)
					out[i] = std::numeric_limits<float>::quiet_NaN();
				transform_writer writer;
				writer.out = out;
				writer.num_tracks = num_tracks;
				for (uint32_t i = 0; i < num_samples; ++i)
				{
					writer.sample = i;
					context.seek(float(i) / rate, acl::sample_rounding_policy::nearest);
					context.decompress_tracks(writer);
				}
				result = int64_t(needed);
			}
		}
		else
		{
			acl::decompression_context<scalar_settings> context;
			if (context.initialize(*tracks))
			{
				scalar_writer writer;
				writer.out = out;
				writer.stride = num_tracks * width;
				for (uint32_t i = 0; i < num_samples; ++i)
				{
					writer.sample = i;
					context.seek(float(i) / rate, acl::sample_rounding_policy::nearest);
					context.decompress_tracks(writer);
				}
				result = int64_t(needed);
			}
		}
		_aligned_free(owned);
		return result;
	}
}
