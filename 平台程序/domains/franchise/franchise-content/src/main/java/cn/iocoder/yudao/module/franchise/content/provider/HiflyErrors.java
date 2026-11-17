package cn.iocoder.yudao.module.franchise.content.provider;
import java.util.Map;
/** 业务错误码共用中文提示，不把供应商密钥或签名地址写入客户端错误。 */
public final class HiflyErrors {
 private HiflyErrors(){}
 private static final Map<Integer,String> M=Map.ofEntries(
 Map.entry(11,"制作参数不正确，请检查素材和文案"),Map.entry(14,"未找到所选人物或声音资源"),Map.entry(1001,"制作任务并发已达上限，请稍后重试"),Map.entry(1002,"数字人服务余额不足，请联系管理员"),Map.entry(1005,"数字人服务需要开通会员，请联系管理员"),Map.entry(1006,"当前服务套餐权限不足"),Map.entry(1009,"高保真声音服务已售罄，请联系管理员"),Map.entry(1011,"声音与名人相似，请更换已获授权的本人声音"),Map.entry(1013,"声音克隆数量已达上限（API默认200个），请联系管理员"),Map.entry(1015,"作品提交数量已达上限，请稍后重试"),Map.entry(2003,"数字人服务凭据无效，请联系管理员"),Map.entry(2011,"素材大小超过限制：视频≤500MB，声音样本≤20MB"),Map.entry(2012,"素材格式不支持：视频须MP4/MOV、H.264编码；声音须MP3/M4A/WAV"),Map.entry(2013,"无法读取声音素材，请重新上传"),Map.entry(2014,"无法读取视频素材，请重新上传"),Map.entry(2015,"数字人克隆失败，请检查视频时长、画面和人物质量"),Map.entry(2016,"无法读取图片素材，请重新上传"));
 public static String message(int code){return M.getOrDefault(code,"制作服务返回错误（"+code+"），请联系管理员");}
}
