
#ifndef MICRODB_DB_H_
#define MICRODB_DB_H_

#include <stdio.h>

namespace microdb{
    
    static const int kMajorVersion = 1;
    static const int kMinorVersion = 0;
    
    class DB {
    public:
        static Status Open(const std::string& dbdirpath, DB** dbptr);
        
        virtual ~DB();
        
        virtual Status Put(const std::string& value, std::string* key) = 0;
    };
}

#endif