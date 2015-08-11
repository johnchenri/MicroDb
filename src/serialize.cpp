
#include <microdb/value.h>
#include <microdb/serialize.h>

#include <cstring>

using namespace std;

namespace microdb {
	
	#define DEFAULT_SIZE 1024
	
	MemOutputStream::MemOutputStream()
	: mBuffer(new int8_t[DEFAULT_SIZE]), mBufSize(DEFAULT_SIZE), mWriteIndex(0) {
	}
	
	void MemOutputStream::Write(const void* buf, const size_t len) {
		const uint32_t available = mBufSize - mWriteIndex;
		if(len > available) {
			//resize
			const uint32_t newBufSize = mBufSize*2;
			unique_ptr<int8_t[]> newBuf(new int8_t[newBufSize]);
			memcpy(newBuf.get(), mBuffer.get(), mWriteIndex);
			mBuffer = move(newBuf);
			mBufSize = newBufSize;
		}
		
		memcpy(&mBuffer.get()[mWriteIndex], buf, len);
		mWriteIndex += len;
	}
	
	void MemOutputStream::GetData(void*& buf, uint32_t& size) const {
		buf = mBuffer.get();
		size = mWriteIndex;
	}
	
	
} // namespace microdb