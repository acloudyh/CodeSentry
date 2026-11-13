package io.codesentry.ai.service;

import com.fasterxml.jackson.databind.JsonNode;

/** 工作线程上的单次审核预算上下文，覆盖真实 HTTP 请求及模型内部重试；退出任务时必须清理。 */
public final class AiReviewBudgetContext {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private static class Scope {final AiReviewCostControl control;final Long jobId;Scope(AiReviewCostControl c,Long id){control=c;jobId=id;}}
    private AiReviewBudgetContext() {}
    public static void open(AiReviewCostControl control,Long jobId){CURRENT.set(new Scope(control,jobId));}
    public static void close(){CURRENT.remove();}
    public static int outputLimit(int fallback){Scope s=CURRENT.get();return s==null?fallback:Math.min(fallback,s.control.outputLimit(s.jobId));}
    public static void reserve(String request,int output){Scope s=CURRENT.get();if(s!=null)s.control.reserve(s.jobId,request,output);}
    public static void usage(JsonNode usage){Scope s=CURRENT.get();if(s!=null)s.control.recordUsage(s.jobId,usage);}
}
