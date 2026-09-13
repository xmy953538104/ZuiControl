// The runner extracts the actual production Identity/statIdentity definitions.
// No copied replacement parser or filesystem mock is used for the new result.
#include "ZUIoptStatParserUnderTest.h"
#include <chrono>
#include <iostream>
#include <random>
#include <sstream>
#include <vector>
#include <algorithm>
#include <iomanip>

namespace Baseline {
using ZUIopt::Identity;
// Frozen f98910e reference: keep its permissive behavior, including partial numbers.
inline Identity statIdentity(const std::string& s){
    Identity out;auto n=s.rfind(") ");if(n==s.npos)return out;std::istringstream in(s.substr(n+2));std::vector<std::string> a;std::string t;
    while(in>>t)a.push_back(t);if(a.size()<20)return out;
    try{out.start=std::stoull(a[19]);out.ticks=std::stoull(a[11])+std::stoull(a[12]);out.state=a[0][0];}catch(...){return {};}
    if(out.state=='Z'||out.state=='X')out.start=0;return out;
}
}

std::string line(std::string prefix="42 (game) ",std::string state="S",std::string start="123",std::string u="10",std::string v="20",unsigned count=50){
    std::vector<std::string> a(count,"0");
    if(count>0)a[0]=state;if(count>11)a[11]=u;if(count>12)a[12]=v;if(count>19)a[19]=start;
    for(auto& t:a)prefix+=t+" ";return prefix;
}
bool accepted(const std::string& s,const ZUIopt::Identity& id){
    return s.empty()||id.start||id.state=='Z'||id.state=='X';
}
void equal(const std::string& s,const std::string& label){
    const auto a=Baseline::statIdentity(s),b=ZUIopt::statIdentity(s);
    if(a.start==b.start&&a.ticks==b.ticks&&a.state==b.state&&accepted(s,a)==accepted(s,b))return;
    std::cerr<<"FIRST_MISMATCH="<<label<<" input_hex=";
    for(unsigned char c:s)std::cerr<<std::hex<<std::setw(2)<<std::setfill('0')<<static_cast<unsigned>(c);
    std::cerr<<std::dec<<" old="<<a.start<<','<<a.ticks<<','<<static_cast<int>(a.state)<<','<<accepted(s,a)
             <<" new="<<b.start<<','<<b.ticks<<','<<static_cast<int>(b.state)<<','<<accepted(s,b)<<'\n';
    throw std::runtime_error("FAIL_BASELINE_SEMANTIC_MISMATCH");
}
std::vector<std::string> manual(){
    std::vector<std::string> v={line(),line("42 (game) ","S","-1"),line("42 (game) ","S","123junk"),
        line("42 game) "),line("42 (game) ","Sleeping"),line("42 (game) ","?"),
        line("42 (game) ","S","123","18446744073709551615","1"),
        line("42 (game) ","S","18446744073709551616"),line("42 (game) ","S","junk"),
        "","42 (game S 0 0",line("42 (game) ","Z")};
    for(auto comm:{"plain","with spaces","left(","right)","a (() ) b", "abcdefghijklmnop"})
        for(auto state:{"R","S","D","T","t","I","Z","X"})v.push_back(line("42 ("+std::string(comm)+") ",state));
    v.push_back(line("42 ("+std::string(4096,'a')+" () ) "));
    const std::vector<std::string> numbers={"0","1","4294967296","18446744073709551615","18446744073709551616",
        "+123","-123","-18446744073709551615","-18446744073709551616","123junk","0x123","01",
        "","junk","+","-","++1","--1"," 123","\t123","123\n456",std::string("123\0junk",8),std::string(4096,'9')};
    for(const auto& n:numbers){v.push_back(line("42 (x) ","S",n));v.push_back(line("42 (x) ","S","123",n));v.push_back(line("42 (x) ","S","123","10",n));}
    for(unsigned count=0;count<=60;count++)v.push_back(line("42 (x) ","S","123","10","20",count));
    for(auto state:{""," ","\t","Sleeping","?","ZZ","XX","Zjunk","x","\nS"})v.push_back(line("42 (x) ",state));
    for(auto suffix:{"","\n","\r\n"," junk suffix", ") S", " ) "})v.push_back(line()+suffix);
    auto normal=line();for(size_t at=0;at<=normal.size();at++)v.push_back(normal.substr(0,at));
    for(char space:{' ','\t','\r','\n','\v','\f'}){
        auto s=line();for(size_t i=s.rfind(") ")+2;i<s.size();i++)if(s[i]==' ')s[i]=space;v.push_back(s);
    }
    v.push_back("no opening delimiter "+line());v.push_back("no closing delimiter");
    return v;
}
void differential(){
    auto corpus=manual();for(size_t i=0;i<corpus.size();i++)equal(corpus[i],"manual_"+std::to_string(i));
    std::cout<<"HANDWRITTEN_CASE_COUNT="<<corpus.size()<<";HANDWRITTEN_DIFFERENTIAL=PASS;BASELINE_12_CASES=PASS;COMM_COMPATIBILITY=PASS;STATE_COMPATIBILITY=PASS;NUMERIC_COMPATIBILITY=PASS;MALFORMED_COMPATIBILITY=PASS\n";
    constexpr uint32_t seed=0x2257a71u;constexpr unsigned count=20000;std::mt19937 rng(seed);
    const std::string alphabet="abXYZ019 ()\t\n_+-?";
    const std::vector<std::string> numbers={"0","+1","-1","12junk","18446744073709551615","18446744073709551616","", "oops","+","-","0x20", "\t123"};
    const std::vector<std::string> states={"R","S","D","T","t","I","Z","X","Sleeping","?",""," \t"};
    for(unsigned i=0;i<count;i++){
        std::string comm;auto length=rng()%80;while(length--)comm+=alphabet[rng()%alphabet.size()];
        std::vector<std::string> a(rng()%65,"0");
        for(auto& t:a){
            if(rng()%2)t=numbers[rng()%numbers.size()];
            else{t.clear();auto digits=rng()%28;while(digits--)t+=static_cast<char>('0'+rng()%10);if(rng()%3==0)t="-"+t;if(rng()%3==0)t+="junk";}
        }
        if(!a.empty())a[0]=states[rng()%states.size()];
        std::string s=(rng()%4?"42 (":"42 ")+comm+(rng()%5?") ":" ");
        for(auto& t:a){s+=t;s+=std::string(1," \t\r\n\v\f"[rng()%6]);if(rng()%3==0)s+=' ';}
        if(rng()%5==0)s+="suffix junk";
        if(rng()%4==0&&!s.empty())s.resize(rng()%(s.size()+1));
        if(rng()%10==0&&!s.empty())s[rng()%s.size()]='\0';
        equal(s,"generated_"+std::to_string(i));
    }
    std::cout<<"FUZZ_SEED="<<seed<<";FUZZ_CASE_COUNT="<<count<<";FUZZ_MATCH_COUNT="<<count<<";FUZZ_MISMATCH_COUNT=0;FUZZ_DIFFERENTIAL=PASS;FIRST_MISMATCHES=NONE\n";
}
// Test stream-locale equivalence as well as the production default classic locale.
struct CustomSpace:std::ctype<char>{
    static const mask* table(){static const auto t=[](){std::array<mask,table_size> a{};std::copy_n(classic_table(),table_size,a.begin());a[static_cast<unsigned>('~')]|=space;return a;}();return t.data();}
    CustomSpace():std::ctype<char>(table()){}
};
void localeCase(){
    const std::locale prior;std::locale::global(std::locale(prior,new CustomSpace));
    auto s=line();auto at=s.rfind(") ")+2;for(;at<s.size();at++)if(s[at]==' ')s[at]='~';
    equal(s,"custom_locale");std::locale::global(prior);std::cout<<"LOCALE_DIFFERENTIAL=PASS\n";
}
volatile uint64_t sink=0;
void benchmark(){
    std::vector<std::string> corpus;
    for(unsigned i=0;i<64;i++)corpus.push_back(line("12345 (Render (worker) "+std::to_string(i)+") ",i%2?"S":"R",std::to_string(300000+i),std::to_string(100000+i),std::to_string(1000+i)));
    constexpr unsigned parses=1000000;std::vector<double> oldTimes,newTimes;
    auto run=[&](bool old,unsigned iterations){auto begin=std::chrono::steady_clock::now();uint64_t sum=0;
        for(unsigned i=0;i<iterations;i++){auto id=old?Baseline::statIdentity(corpus[i%corpus.size()]):ZUIopt::statIdentity(corpus[i%corpus.size()]);sum+=id.start+id.ticks+id.state;}
        sink=sum;return std::chrono::duration<double>(std::chrono::steady_clock::now()-begin).count();};
    run(true,100000);run(false,100000);
    for(unsigned r=0;r<5;r++){double a,b;if(r%2){b=run(false,parses);a=run(true,parses);}else{a=run(true,parses);b=run(false,parses);}oldTimes.push_back(a);newTimes.push_back(b);std::cout<<"ROUND="<<r+1<<" OLD_SECONDS="<<a<<" NEW_SECONDS="<<b<<'\n';}
    std::sort(oldTimes.begin(),oldTimes.end());std::sort(newTimes.begin(),newTimes.end());
    std::cout<<"BENCHMARK_ROUNDS=5;PARSES_PER_ROUND="<<parses<<";OLD_MEDIAN_SECONDS="<<oldTimes[2]<<";NEW_MEDIAN_SECONDS="<<newTimes[2]<<";OLD_P95_SECONDS="<<oldTimes.back()<<";NEW_P95_SECONDS="<<newTimes.back()<<";MEDIAN_SPEEDUP="<<oldTimes[2]/newTimes[2]<<";P95_METHOD=NEAREST_RANK;ALLOCATION_METRIC=NOT_MEASURED\n";
}
int main(int argc,char** argv){try{std::cout<<std::unitbuf<<std::setprecision(10);
    if(argc==1){differential();localeCase();}
    else if(argc==2&&std::string(argv[1])=="--benchmark")benchmark();
    else return 2;
    return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
