package cn.iocoder.yudao.module.platform.authz.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Administrative choices do not depend on whether any franchise occupies the region. */
@Service
public class FranchiseRegionCatalog {
    public record Region(String code,String name,String level,String parentCode,
                         String provinceCode,String provinceName,String cityCode,String cityName,
                         String districtCode,String districtName,Double longitude,Double latitude) { }
    public record Resolution(List<String> codes,String provinceCode,String cityCode,String status,String message) { }
    private final JdbcTemplate jdbc;
    public FranchiseRegionCatalog(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<Region> all() {
        LinkedHashMap<String,Region> regions=new LinkedHashMap<>();
        jdbc.query("SELECT * FROM pf_map_region ORDER BY code",r -> {
            String code=r.getString("code"),pc=r.getString("province_code"),cc=r.getString("city_code");
            String pn=r.getString("province_name"),cn=r.getString("city_name"),dn=r.getString("district_name");
            boolean district=!code.endsWith("00");
            boolean direct=cc.equals(pc)||(district&&cc.equals(code));
            regions.putIfAbsent(pc,new Region(pc,pn,"province",null,pc,pn,"","","","",null,null));
            if(!direct) {
                Region city=new Region(cc,cn,"city",pc,pc,pn,cc,cn,"","",
                        code.equals(cc)?r.getDouble("longitude"):null,code.equals(cc)?r.getDouble("latitude"):null);
                if(!regions.containsKey(cc)||code.equals(cc))regions.put(cc,city);
            }
            if(district)regions.put(code,new Region(code,dn,"district",direct?pc:cc,pc,pn,direct?"":cc,cn,
                    code,dn,r.getDouble("longitude"),r.getDouble("latitude")));
        });
        return regions.values().stream().sorted(Comparator.comparing(Region::code)).toList();
    }

    public List<Region> children(String parent) {
        List<Region> regions=all();
        if(parent==null||parent.isBlank())return regions;
        if(regions.stream().noneMatch(r->r.code().equals(parent)))throw invalid("行政区域不存在");
        return regions.stream().filter(r->parent.equals(r.parentCode())).toList();
    }

    /** Exact/uniquely abbreviated administrative names only; commercial areas remain unresolved. */
    public Resolution resolve(String province,String city,String original,List<String> explicitCodes) {
        return resolve(all(),province,city,original,explicitCodes);
    }

    static Resolution resolve(List<Region> regions,String province,String city,String original,List<String> explicitCodes) {
        Map<String,Region> byCode=new HashMap<>();regions.forEach(r->byCode.put(r.code(),r));
        String pc=hint(regions,province,"province","");
        if(!clean(province).isEmpty()&&pc.isEmpty())throw invalid("省份无法唯一识别，请填写标准省级名称或代码");
        String cc=hint(regions,city,"city",pc);
        if(!clean(city).isEmpty()&&cc.isEmpty()) {
            String municipality=municipality(regions,city);
            if(!municipality.isEmpty()&&(pc.isEmpty()||pc.equals(municipality)))pc=municipality;
            else throw invalid("城市无法唯一识别或与省份不一致；省直辖区县请将城市列留空");
        }
        if(!cc.isEmpty()&&pc.isEmpty())pc=byCode.get(cc).provinceCode();
        if(explicitCodes!=null&&!explicitCodes.isEmpty()) {
            LinkedHashSet<String> codes=new LinkedHashSet<>();
            for(String value:explicitCodes) {
                String code=clean(value);Region r=byCode.get(code);
                if(r==null)throw invalid("地区代码不存在："+code);
                if(!pc.isEmpty()&&!pc.equals(r.provinceCode()))throw invalid("地区代码与省份不一致");
                if(!cc.isEmpty()&&!cc.equals(r.cityCode()))throw invalid("地区代码与城市不一致");
                codes.add(code);
            }
            return finish(byCode,codes,pc,cc,false);
        }
        String raw=clean(original);
        LinkedHashSet<String> codes=new LinkedHashSet<>();boolean unknown=false;
        Set<String> provinces=new HashSet<>(),cities=new HashSet<>();
        String inheritedProvince=pc,inheritedCity=cc;
        for(String part:raw.split("[、,，/;；+＋|\\n]+")) {
            String token=part.trim();if(token.isEmpty())continue;
            Part result=resolvePart(regions,token,inheritedProvince,inheritedCity);
            if((!pc.isEmpty()&&!result.provinceCode().isEmpty()&&!pc.equals(result.provinceCode()))
                    ||(!cc.isEmpty()&&!result.cityCode().isEmpty()&&!cc.equals(result.cityCode()))) {
                unknown=true;
                continue;
            }
            if(!result.provinceCode().isEmpty()) {
                inheritedProvince=result.provinceCode();inheritedCity=result.cityCode();
                provinces.add(result.provinceCode());cities.add(result.cityCode());
            }
            if(!result.code().isEmpty())codes.add(result.code());
            unknown|=result.unknown();
        }
        if(provinces.size()==1)pc=provinces.iterator().next();
        if(cities.size()==1)cc=cities.iterator().next();
        Resolution result=finish(byCode,codes,pc,cc,unknown||raw.isBlank());
        return new Resolution(result.codes(),provinces.size()>1?"":result.provinceCode(),
                cities.size()>1?"":result.cityCode(),result.status(),result.message());
    }

    private record Part(String code,String provinceCode,String cityCode,boolean unknown) { }
    private record Prefix(Region region,String remaining) { }

    private static Part resolvePart(List<Region> regions,String original,String province,String city) {
        String token=original.replaceFirst("(?:[（(]?(?:大区|全市|全省|市区|片区|区域)[）)]?)$","").trim();
        boolean broad=!token.equals(original);
        String pc=province,cc=city;
        Prefix provincePrefix=prefix(regions,token,"province","",false);
        if(provincePrefix!=null) {
            if(!provincePrefix.region().code().equals(pc))cc="";
            pc=provincePrefix.region().code();token=provincePrefix.remaining();
            if(token.isEmpty())return part(provincePrefix.region(),broad);
        }

        // A county can share a city's stem, e.g. 市/县. Match the explicit county before stripping a city abbreviation.
        Part district=district(regions,token,pc,cc,broad);
        if(district!=null)return district;
        Prefix cityPrefix=prefix(regions,token,"city",pc,false);
        if(cityPrefix!=null) {
            pc=cityPrefix.region().provinceCode();cc=cityPrefix.region().code();token=cityPrefix.remaining();
            if(token.isEmpty())return part(cityPrefix.region(),broad);
        }
        district=district(regions,token,pc,cc,broad);
        if(district!=null)return district;
        // Preserve a development zone/town's known parents without assigning a neighbouring county or expanding all children.
        return new Part("",pc,cc,true);
    }

    private static Part district(List<Region> regions,String token,String province,String city,boolean broad) {
        var scoped=regions.stream().filter(r->r.level().equals("district"))
                .filter(r->province.isEmpty()||province.equals(r.provinceCode()))
                .filter(r->city.isEmpty()||city.equals(r.cityCode())).toList();
        var exact=scoped.stream().filter(r->r.name().equals(token)||r.code().equals(token)).toList();
        if(exact.isEmpty()) {
            boolean otherLevel=regions.stream().filter(r->!r.level().equals("district"))
                    .filter(r->province.isEmpty()||province.equals(r.provinceCode()))
                    .anyMatch(r->r.name().equals(token)||normalize(r.name()).equals(token));
            if(!otherLevel)exact=matches(scoped,token);
        }
        if(exact.size()==1)return part(exact.get(0),broad);
        // The explicit official county name is reliable even when followed by a town; coordinates remain county centres.
        Prefix county=prefix(scoped,token,"district",province,true);
        if(county==null||county.remaining().isEmpty())return null;
        boolean town=county.remaining().matches(".+(?:镇|乡|街道|村)(?:.*)")
                &&!county.remaining().matches(".*(?:开发区|新区|园区|片区|大区|区域).*" );
        return part(county.region(),broad||!town);
    }

    private static Part part(Region region,boolean unknown) {
        return new Part(region.code(),region.provinceCode(),region.cityCode(),unknown);
    }

    private static Prefix prefix(List<Region> regions,String value,String level,String province,boolean fullNameOnly) {
        var scoped=regions.stream().filter(r->r.level().equals(level))
                .filter(r->province.isEmpty()||province.equals(r.provinceCode())).toList();
        var full=scoped.stream().filter(r->value.startsWith(r.name())).toList();
        Prefix result=longestPrefix(full,value,false);
        if(result!=null||!full.isEmpty()||fullNameOnly)return result;
        var shortNames=scoped.stream().filter(r->{
            String shortName=normalize(r.name());
            if(shortName.isEmpty()||!value.startsWith(shortName))return false;
            String suffix=value.substring(shortName.length());
            if(suffix.isEmpty()&&regions.stream().filter(candidate->province.isEmpty()||province.equals(candidate.provinceCode()))
                    .filter(candidate->candidate.name().equals(value)||normalize(candidate.name()).equals(value)).count()>1)return false;
            return !suffix.matches("^[省市区县旗].*");
        }).toList();
        return longestPrefix(shortNames,value,true);
    }

    private static Prefix longestPrefix(List<Region> matches,String value,boolean shortName) {
        int length=matches.stream().mapToInt(r->(shortName?normalize(r.name()):r.name()).length()).max().orElse(0);
        var best=matches.stream().filter(r->(shortName?normalize(r.name()):r.name()).length()==length).toList();
        return best.size()==1?new Prefix(best.get(0),value.substring(length).trim()):null;
    }

    private static Resolution finish(Map<String,Region> regions,Set<String> codes,String pc,String cc,boolean unknown) {
        Set<String> provinces=new HashSet<>(),cities=new HashSet<>();boolean onlyDistricts=!codes.isEmpty();
        for(String code:codes){Region r=regions.get(code);provinces.add(r.provinceCode());cities.add(r.cityCode());onlyDistricts&=r.level().equals("district");}
        if(pc.isEmpty()&&provinces.size()==1)pc=provinces.iterator().next();
        if(cc.isEmpty()&&cities.size()==1)cc=cities.iterator().next();
        String status=codes.isEmpty()?"UNRESOLVED":onlyDistricts&&!unknown?"RESOLVED":"PARTIAL";
        String message=switch(status){case "RESOLVED"->"";case "PARTIAL"->"保留原始覆盖范围，区县资料待确认";default->"地区未能唯一匹配，已保留原文待确认";};
        return new Resolution(List.copyOf(codes),pc,cc,status,message);
    }
    private static String hint(List<Region> regions,String value,String level,String province) {
        String text=clean(value);if(text.isEmpty())return "";
        var list=regions.stream().filter(r->r.level().equals(level)&&(province.isEmpty()||province.equals(r.provinceCode())))
                .filter(r->r.code().equals(text)||r.name().equals(text)||normalize(r.name()).equals(text)).toList();
        return list.size()==1?list.get(0).code():"";
    }
    private static String municipality(List<Region> regions,String value) {
        String text=clean(value);
        var matches=regions.stream().filter(r->r.level().equals("province"))
                .filter(r->r.code().equals(text)||r.name().equals(text)||normalize(r.name()).equals(text))
                .filter(r->regions.stream().anyMatch(child->child.level().equals("district")&&child.provinceCode().equals(r.code())
                        &&child.cityCode().isEmpty()&&child.cityName().equals(r.name()))).toList();
        return matches.size()==1?matches.get(0).code():"";
    }
    private static List<Region> matches(List<Region> regions,String token) {
        String value=clean(token);if(value.isEmpty())return List.of();
        var exact=regions.stream().filter(r->r.name().equals(value)||r.code().equals(value)).toList();
        if(!exact.isEmpty())return exact;
        return regions.stream().filter(r->normalize(r.name()).equals(value)).toList();
    }
    static String normalize(String value) { return clean(value).replaceFirst("(特别行政区|维吾尔自治区|壮族自治区|回族自治区|自治区|自治州|地区|省|市|区|县)$",""); }
    static String clean(String value) { return value==null?"":value.trim(); }
    private static ServiceException invalid(String message) { return new ServiceException(400,message); }
}
