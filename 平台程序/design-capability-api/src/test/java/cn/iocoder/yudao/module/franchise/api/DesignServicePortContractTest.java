package cn.iocoder.yudao.module.franchise.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignServicePortContractTest {

    @Test
    void contractLivesInStandaloneModule() {
        Path source = Path.of(System.getProperty("basedir", "."),
                "src/main/java/cn/iocoder/yudao/module/franchise/api");
        assertTrue(Files.exists(source.resolve("DesignServicePort.java")));
        assertTrue(Files.exists(source.resolve("FranchiseDesignErrorCodes.java")));
    }

    @Test
    void allMethodNamesParametersAndReturnsRemainCompatible() {
        Set<String> expected = Set.of(
                "createProject(long,DesignProjectCreate,Actor):ProjectCreated",
                "designFlow(long,cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall,Actor):Object",
                "listProjects(long,String,int):ProjectPageView",
                "getProject(long,long,Long):ProjectDetailView",
                "getAiJob(long,long):AiJobView",
                "cancelAiJob(long,long):AiJobView",
                "notePolish(long,String,String):String",
                "listResultVersions(long,long):List<ResultVersionView>",
                "createFlatJob(long,long,FlatJobCreate,Actor):JobSubmitted",
                "createElevationJob(long,long,ElevationJobCreate,Actor):JobSubmitted",
                "confirmFlatCandidate(long,long,Long,long,Actor):SelectionSaved",
                "selectElevationCandidate(long,long,Long,long,Actor):SelectionSaved",
                "createRevisionRequest(long,long,RevisionCreate,Actor):RevisionSubmitted",
                "validateSchemeRef(long,long,String,Long,Long):SchemeRef",
                "validateAsset(long,long):void",
                "listCases(CaseQuery):CasePage",
                "listCaseStyles():List<StyleOption>",
                "getCaseDetail(long):CaseDetailView",
                "favoriteCase(long,long):void",
                "unfavoriteCase(long,long):void",
                "listFavorites(long,String,int):CasePage",
                "budgetRuleInfo(String,String,String):RuleInfoView",
                "createBudgetEstimate(long,long,BudgetEstimateCreate,Actor):EstimateCreated",
                "createUploadTicket(long,UploadTicketCreate):UploadTicketView",
                "completeUpload(long,long):AssetCompleted",
                "createDownloadUrl(long,long):DownloadUrlView");
        Set<String> actual = Arrays.stream(DesignServicePort.class.getDeclaredMethods())
                .map(method -> method.getName() + "(" + Arrays.stream(method.getGenericParameterTypes())
                        .map(DesignServicePortContractTest::typeName).collect(Collectors.joining(","))
                        + "):" + typeName(method.getGenericReturnType()))
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }

    @Test
    void recordComponentOrderTypesAndConstructorsRemainCompatible() throws Exception {
        Set<String> expected = Set.of(
                "Actor(actorUserId:long,actorPostId:long,actorRoleCode:String)",
                "DesignProjectCreate(sourceType:String,refCaseId:Long,sketchAssetId:Long,requirementInputs:Map<String,Object>,line:String,idempotencyKey:String)",
                "ProjectCreated(projectId:long,replayed:boolean)",
                "ProjectListItem(projectId:long,sourceType:String,refCaseId:Long,stage:String,status:String,name:String,createTime:String)",
                "ProjectPageView(list:List<ProjectListItem>,nextCursor:String)",
                "ProjectDetailView(fields:Map<String,Object>)",
                "AiJobView(jobId:long,status:String,requestedCount:int,acceptedCount:int,progress:int,phase:String,allowedActions:List<String>,projectId:String,imageOptions:Map<String,String>,unitPointCost:Long,totalPointCost:Long,refundedPointCost:Long,netPointCost:Long,createdAt:String,finishedAt:String)",
                "ResultVersionView(versionId:long,version:long,superseded:boolean,selectedFlatAssetId:Long,selectedElevationAssetId:Long,configSnapshotJson:String,createTime:String)",
                "FlatJobCreate(count:int,resolution:String,orientation:String,idempotencyKey:String)",
                "ElevationJobCreate(count:int,resolution:String,orientation:String,styleCode:String,roofType:String,material:String,color:String,idempotencyKey:String)",
                "JobSubmitted(jobId:long,replayed:boolean)",
                "SelectionSaved(resultVersionId:Long,version:Long,selectionSaved:boolean,replayed:boolean)",
                "RevisionCreate(reason:String,configUpdates:Map<String,Object>,count:Integer,resolution:String,orientation:String,idempotencyKey:String)",
                "RevisionSubmitted(requestId:long,newJobId:long,replayed:boolean)",
                "SchemeRef(resultVersionId:Long,flatCandidateIds:List<Long>,selectedFlatAssetId:Long,selectedElevationAssetId:Long,configSnapshotJson:String,estimateId:Long,totalLowCents:Long,totalHighCents:Long,estimateArea:Integer)",
                "CaseQuery(styleCode:String,floorCount:Integer,faceWidth:BigDecimal,depth:BigDecimal,keyword:String,cursor:String,limit:int,sourceType:String,minArea:Integer,maxArea:Integer)",
                "StyleOption(code:String,label:String)",
                "CasePage(list:List<Map<String,Object>>,nextCursor:String)",
                "CaseDetailView(fields:Map<String,Object>)",
                "RuleInfoView(regionCode:String,structureType:String,materialGrade:String,ruleVersionId:long,lowCentsPerSqm:long,highCentsPerSqm:long,effectiveAt:String)",
                "BudgetEstimateCreate(regionCode:String,structureType:String,materialGrade:String,buildingArea:int,resultVersionId:Long,idempotencyKey:String)",
                "EstimateCreated(estimateId:long,totalLowCents:long,totalHighCents:long,ruleVersionId:long,replayed:boolean)",
                "UploadTicketCreate(assetType:String,mimeType:String,sizeBytes:long,sha256:String)",
                "UploadTicketView(assetId:long,uploadUrl:String)",
                "AssetCompleted(assetId:long,uploadStatus:String,rejectedReason:String)",
                "DownloadUrlView(downloadUrl:String,expiresAt:Long)");
        Set<String> actual = Arrays.stream(DesignServicePort.class.getDeclaredClasses())
                .map(record -> record.getSimpleName() + "(" + Arrays.stream(record.getRecordComponents())
                        .map(component -> component.getName() + ":" + typeName(component.getGenericType()))
                        .collect(Collectors.joining(",")) + ")")
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
        for (Class<?> record : DesignServicePort.class.getDeclaredClasses()) {
            assertTrue(record.isRecord());
            Class<?>[] types = Arrays.stream(record.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
            assertTrue(java.lang.reflect.Modifier.isPublic(record.getConstructor(types).getModifiers()));
        }
    }

    @Test
    void publishedErrorCodesRemainCompatible() {
        assertEquals(1_112_000_001, FranchiseDesignErrorCodes.SCHEME_SIZE_REQUIRED.getCode());
        assertEquals(1_112_000_002, FranchiseDesignErrorCodes.CUSTOMER_NOT_ACCESSIBLE.getCode());
        assertEquals(1_112_000_003, FranchiseDesignErrorCodes.SCHEME_KIND_MISMATCH.getCode());
        assertEquals(1_112_000_004, FranchiseDesignErrorCodes.SERVICE_ACCOUNT_INACTIVE.getCode());
    }

    private static String typeName(java.lang.reflect.Type type) {
        return type.getTypeName().replace("java.lang.", "").replace("java.util.", "")
                .replace("java.math.", "")
                .replace(DesignServicePort.class.getName() + "$", "").replace(" ", "");
    }
}
