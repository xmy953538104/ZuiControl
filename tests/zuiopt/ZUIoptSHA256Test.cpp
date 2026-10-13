#include "../../native/zuiopt/ZUIopt_sha256.h"
#include <iostream>
#include <stdexcept>
#include <vector>
int main(){try{
    std::vector<std::pair<std::string,std::string>> vectors={
        {"","e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
        {"abc","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
        {"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq","248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"},
        {std::string(1000000,'a'),"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"},
{std::string(55,'\0'),"02779466cdec163811d078815c633f21901413081449002f24aa3e80f0b88ef7"},
{std::string(56,'\0'),"d4817aa5497628e7c77e6b606107042bbba3130888c5f47a375e6179be789fbb"},
{std::string(63,'\0'),"c7723fa1e0127975e49e62e753db53924c1bd84b8ac1ac08df78d09270f3d971"},
{std::string(64,'\0'),"f5a5fd42d16a20302798ef6ed309979b43003d2320d9f0e8ea9831a92759fb4b"},
{std::string(65,'\0'),"98ce42deef51d40269d542f5314bef2c7468d401ad5d85168bfab4c0108f75f7"}};
    for(const auto& [input,expected]:vectors)if(ZUIopt::sha256(input)!=expected)throw std::runtime_error("SHA256 standard vector mismatch");
    std::cout<<"SHA256_STANDARD_VECTORS=9;EMPTY_SHORT_PADDING_55_56_63_64_65_MULTIBLOCK_MILLION=PASS\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
