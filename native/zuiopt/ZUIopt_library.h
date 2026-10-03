// Rule-library transformations only; native scheduling/evaluation remains unchanged.
#pragma once
#include "ZUIopt_rules.h"
namespace ZUIopt {
inline std::string behavior(Profile p){
    // Numeric priorities are an encoding of order, not an independent behavior.
    for(size_t i=0;i<p.rules.size();i++)p.rules[i].priority=100000-static_cast<int>(i);
    return profileText(p);
}
inline const Profile* appBehavior(const Config& c,const std::string& package){
    // Library contents remain inspectable when the global engine switch is off.
    for(const auto& m:c.packages)if(match(m.kind,m.package,package))return &c.profiles.at(m.profile);
    return nullptr;
}
inline std::string behavior(const Profile* p){return p?behavior(*p):"";}
struct LibraryChange {
    std::string package,provenance,decision;bool conflict=false,added=false,removed=false,changed=false;
    bool mappingChanged=false,cpuChanged=false,selectorClassChanged=false;int threadAdded=0,threadRemoved=0,threadChanged=0;
};
struct LibraryMerge {std::string canonical;std::vector<LibraryChange> changes;};
inline LibraryMerge mergeLibrary(const Config& old,const Config& current,const Config& incoming,
        const std::map<std::string,std::string>& decisions={},const Config* manual=nullptr,bool partial=false){
    // Upstream v1 is an enumerated per-App library. Existing advanced wildcard mappings remain intact.
    for(const auto* c:{&old,&incoming})for(const auto& m:c->packages)require(m.kind=="exact","upstream library requires exact package mappings");
    std::set<std::string> names;for(const auto* c:{&old,&current,&incoming})for(const auto& m:c->packages)if(m.kind=="exact")names.insert(m.package);
    require(names.size()<=512,"library package bound");
    for(const auto& [name,decision]:decisions)require(names.count(name)&&(decision=="KEEP_MINE"||decision=="USE_UPSTREAM"||decision=="MANUAL_MERGE"),"library decision");
    Config output;output.enabled=current.enabled;LibraryMerge result;
    std::map<std::string,std::string> aliases;
    auto append=[&](const Mapping& mapping,const Profile& profile){
        auto semantic=behavior(profile);auto it=aliases.find(semantic);std::string alias;
        if(it==aliases.end()){alias="L"+std::to_string(aliases.size());aliases.emplace(semantic,alias);output.profiles.emplace(alias,profile);}
        else alias=it->second;
        output.packages.push_back({mapping.kind,mapping.package,alias,100000-static_cast<int>(output.packages.size())});
    };
    for(const auto& name:names){
        const auto* a=appBehavior(old,name);const auto* b=appBehavior(current,name);const auto* c=appBehavior(incoming,name);
        if(partial&&!c){if(b)append({"exact",name,"",0},*b);continue;}
        auto sa=behavior(a),sb=behavior(b),sc=behavior(c);LibraryChange change;change.package=name;
        change.provenance=sa.empty()?"USER_CREATED":sa==sb?"UPSTREAM":"USER_MODIFIED";
        change.conflict=partial?(b&&sb!=sc):sb!=sa&&sc!=sa&&sb!=sc;
        // User-created rows always survive a full upstream update unless explicitly selected otherwise.
        const Profile* chosen=(sa.empty()&&b)||sb!=sa?b:c;
        if(partial)chosen=b?b:c;
        change.decision=chosen==c?"USE_UPSTREAM":"KEEP_MINE";
        auto decision=decisions.find(name);
        if(decision!=decisions.end()){
            change.decision=decision->second;
            if(decision->second=="KEEP_MINE")chosen=b;
            else if(decision->second=="USE_UPSTREAM")chosen=c;
            else{require(manual,"manual merge missing");chosen=appBehavior(*manual,name);require(chosen,"manual App missing");}
        }else if(change.conflict){chosen=b;change.decision="KEEP_MINE";}
        change.added=!a&&c;change.removed=a&&!c;change.changed=sa!=sc;
        auto mappingIdentity=[&](const Config& config){
            for(size_t i=0;i<config.packages.size();i++){const auto& m=config.packages[i];
                if(match(m.kind,m.package,name))return m.kind+":"+m.package+":"+m.profile+":"+std::to_string(i);}
            return std::string();
        };
        change.mappingChanged=mappingIdentity(old)!=mappingIdentity(incoming);
        change.cpuChanged=a&&c&&a->general!=c->general;
        size_t an=a?a->rules.size():0,cn=c?c->rules.size():0;
        std::vector<bool> paired(cn,false);
        for(size_t i=0;i<an;i++){
            const auto& x=a->rules[i];size_t j=0;
            for(;j<cn;j++)if(!paired[j]&&x.kind==c->rules[j].kind&&x.pattern==c->rules[j].pattern)break;
            if(j==cn){change.threadRemoved++;continue;}
            paired[j]=true;const auto& y=c->rules[j];
            if(x.cls!=y.cls||x.rank!=y.rank)change.selectorClassChanged=true;
            if(x.mask!=y.mask)change.cpuChanged=true;
            if(x.cls!=y.cls||x.rank!=y.rank||x.mask!=y.mask||i!=j)change.threadChanged++;
        }
        for(bool found:paired)if(!found)change.threadAdded++;
        if(chosen)append({"exact",name,"",0},*chosen);result.changes.push_back(std::move(change));
    }
    // Unknown Apps retain the same advanced wildcard order. Named Apps were resolved above.
    for(const auto& m:current.packages)if(m.kind!="exact")append(m,current.profiles.at(m.profile));
    result.canonical=dumpRules(output);require(result.canonical.size()<=RULE_LIMIT,"merged canonical bound");return result;
}
struct LibraryPack {std::string upstream,manual,metadata;std::map<std::string,std::string> decisions;bool partial=false;};
inline LibraryPack libraryPack(const std::string& bytes,const std::string& oldHash){
    auto files=unpackRuleFiles(bytes,{"manifest.json","rules.conf","manual.conf","decisions.conf"});
    auto m=ManifestJson(files.at("manifest.json")).parse();
    const std::set<std::string> keys{"schema","kind","source","sourceVersion","sourceDate","sourceCommit","sourceEvidence","targetSoC","targetTopology","oldUpstreamHash","newUpstreamHash","rulesHash","manualHash","decisionsHash"};
    require(m.size()==keys.size(),"library manifest fields");
    for(const auto& k:keys)require(m.count(k)&&m.at(k).integer==(k=="schema"),"library manifest types");
    auto get=[&](const char* k){return m.at(k).text;};
    require(get("schema")=="1"&&get("targetSoC")=="SM8650"&&get("targetTopology")=="0-7","library compatibility");
    require(!get("source").empty()&&!get("sourceVersion").empty()&&(!get("sourceCommit").empty()||!get("sourceEvidence").empty()),"library provenance");
    require(get("oldUpstreamHash")==oldHash,"stale upstream base");
    LibraryPack p;p.upstream=files.at("rules.conf");p.manual=files.at("manual.conf");p.metadata=jsonText(m);
    require(get("kind")=="UPSTREAM"||get("kind")=="APPOPT","library kind");p.partial=get("kind")=="APPOPT";
    require(dumpRules(rules(p.manual))==p.manual,"library normalized manual rules");
    require(get("newUpstreamHash")== (p.partial?oldHash:sha256(p.upstream))&&get("rulesHash")==sha256(p.upstream)
        &&get("manualHash")==sha256(p.manual)&&get("decisionsHash")==sha256(files.at("decisions.conf")),"library member digest");
    if(p.partial)p.upstream=importAppOpt(p.upstream,"appopt-preview",0).ruleText;
    else require(dumpRules(rules(p.upstream))==p.upstream,"upstream normalized rules");
    std::istringstream lines(files.at("decisions.conf"));std::string line;
    while(std::getline(lines,line)){line=trim(line);if(line.empty()||line[0]=='#')continue;
        std::istringstream fields(line);std::string pkg,decision,extra;fields>>pkg>>decision;
        require(!(fields>>extra)&&packageLabel(pkg)&&p.decisions.emplace(pkg,decision).second&&p.decisions.size()<=512,"library decision row");
    }
    return p;
}
inline std::string libraryPreview(const LibraryMerge& merged,size_t offset){
    require(offset<=merged.changes.size(),"preview offset");std::ostringstream out;
    out<<"{\"canonicalHash\":"<<std::quoted(sha256(merged.canonical))<<",\"count\":"<<merged.changes.size()<<",\"offset\":"<<offset<<",\"apps\":[";
    for(size_t i=offset;i<std::min(offset+32,merged.changes.size());i++){
        if(i!=offset)out<<',';const auto& c=merged.changes[i];
        out<<"{\"package\":"<<std::quoted(c.package)<<",\"provenance\":"<<std::quoted(c.provenance)<<",\"decision\":"<<std::quoted(c.decision)
            <<",\"conflict\":"<<(c.conflict?"true":"false")<<",\"added\":"<<(c.added?"true":"false")<<",\"removed\":"<<(c.removed?"true":"false")
            <<",\"mappingChanged\":"<<(c.mappingChanged?"true":"false")<<",\"profileSemanticChanged\":"<<(c.changed?"true":"false")<<",\"cpuMaskChanged\":"<<(c.cpuChanged?"true":"false")
            <<",\"selectorClassChanged\":"<<(c.selectorClassChanged?"true":"false")<<",\"threadAdded\":"<<c.threadAdded<<",\"threadRemoved\":"<<c.threadRemoved<<",\"threadChanged\":"<<c.threadChanged<<'}';
    }
    out<<"]}";require(out.str().size()<=32768,"preview page bound");return out.str();
}
}
