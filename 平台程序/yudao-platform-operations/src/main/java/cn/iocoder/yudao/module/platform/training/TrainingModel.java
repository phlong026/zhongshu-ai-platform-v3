package cn.iocoder.yudao.module.platform.training;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import java.util.*;

public final class TrainingModel {
    private TrainingModel() { }
    public static final List<String> CATEGORIES=List.of("AI短视频获客","业务销售","工程施工","财务对账","设计交付","经营管理");
    public static final Set<String> ROLES=Set.of("sales","sales-lead","engineer","project-manager","finance","finance-lead","hq-design-admin","hq-designer","hq-design-lead","boss","owner","hq-operations","hq-operations-lead");
    public record Attachment(String versionId,boolean download) { }
    public record Lesson(String id,String title,String chapter,String mainVersionId,List<Attachment> attachments,int completionPercent) { }
    public record Course(String title,String category,String lecturer,String summary,String objective,String coverVersionId,
                         boolean allOrganizations,List<String> organizationIds,List<String> roles,List<String> userIds,
                         String opensAt,String closesAt,List<Lesson> lessons,List<Attachment> attachments) { }
    public record Save(Long expectedRevision,Course content) { }
    public record Action(long expectedRevision,String reason) { }
    public record Upload(String name,String category,String materialId,String fileName,long size,String sha256,String requestKey) { }
    public record MaterialEdit(Long expectedRevision,String name,String category) { }
    public record MaterialDelete(Long expectedRevision) { }
    public record CategoryEdit(Long expectedRevision,String name) { }
    public record CategoryDelete(Long expectedRevision,Long expectedMaterialCount) { }
    public record Beat(String playId,double position) { }
    public static <T>List<T> list(List<T> value){return value==null?List.of():value;}
    public static void check(boolean ok,String message){if(!ok)throw new ServiceException(400,message);}
    public static void text(String text,int max,String name){check(text!=null&&!text.isBlank()&&text.length()<=max,"请正确填写"+name);}
    public static String id(){return UUID.randomUUID().toString();}
}
