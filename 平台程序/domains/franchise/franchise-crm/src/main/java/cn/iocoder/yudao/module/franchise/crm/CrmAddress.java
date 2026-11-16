package cn.iocoder.yudao.module.franchise.crm;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadDO;

/** One normalized address snapshot for capture, update, response and command fingerprint. */
public record CrmAddress(String province, String city, String district, String town, String village,
                         String regionCode, String addressDetail) {
    public static CrmAddress normalize(String province, String city, String district, String town,
                                        String village, String regionCode, String detail) {
        var result = new CrmAddress(trim(province,64),trim(city,64),trim(district,64),trim(town,64),
                trim(village,64),trim(regionCode,12),trim(detail,200));
        result.validate();
        return result;
    }

    private static String trim(String value, int max) {
        String result = StrUtil.trimToNull(value);
        if (result != null && result.length() > max) throw invalid("地址内容超过长度限制");
        return result;
    }

    private void validate() {
        // Older clients and imported records may only contain province/city. Preserve that contract.
        if (regionCode == null && district == null && town == null && village == null) return;
        if (province == null || city == null || district == null || town == null || village == null
                || regionCode == null || !regionCode.matches("(?:\\d{9}|\\d{12})")) {
            throw invalid("请完整选择地址至乡镇和村／社区");
        }
        String[] names = {province,city,district,town,village};
        int[] lengths = {2,4,6,9,12};
        String parent = "";
        for (int index=0;index<names.length;index++) {
            var options = CrmRegions.children(parent);
            if (index==4 && regionCode.length()==9 && options.isEmpty()) return; // Explicit manual village fallback.
            String code = regionCode.substring(0,Math.min(lengths[index],regionCode.length()));
            final String name=names[index];
            if (options.stream().noneMatch(option->option.code().equals(code)&&option.name().equals(name))) {
                throw invalid("地址层级不匹配，请重新选择");
            }
            parent=code;
        }
    }

    public CustomerDO apply(CustomerDO customer) {
        return customer.setProvince(province).setCity(city).setDistrict(district).setTown(town)
                .setVillage(village).setRegionCode(regionCode).setAddressDetail(addressDetail);
    }

    public LeadDO apply(LeadDO lead) {
        return lead.setProvince(province).setCity(city).setDistrict(district).setTown(town)
                .setVillage(village).setRegionCode(regionCode).setAddressDetail(addressDetail);
    }

    private static ServiceException invalid(String message) { return new ServiceException(400,message); }
}
