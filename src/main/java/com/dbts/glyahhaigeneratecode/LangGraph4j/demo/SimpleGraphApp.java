package com.dbts.glyahhaigeneratecode.LangGraph4j.demo;

import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.GraphStateException;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.StateGraph.END;

import java.util.List;
import java.util.Map;

public class SimpleGraphApp {

    public static void main(String[] args) throws GraphStateException {
        // 初始化节点
        GreeterNode greeterNode = new GreeterNode();
        ResponderNode responderNode = new ResponderNode();

        // 定义图结构
       var stateGraph = new StateGraph<>(SimpleState.SCHEMA, initData -> new SimpleState(initData))
            .addNode("greeter", node_async(greeterNode))
            .addNode("responder", node_async(responderNode))
            // 定义边
            .addEdge(START, "greeter") // Start with the greeter node
            .addEdge("greeter", "responder")
            .addEdge("responder", END)   // End after the responder node
             ;
        // 编译图
        var compiledGraph = stateGraph.compile();

        GraphRepresentation tu = stateGraph.getGraph(GraphRepresentation.Type.MERMAID, "图图图", true);
        System.out.println(tu);

//        运行图表
//        “流”方法返回一个异步生成器。
//        为了简化，我们会收集结果。在真实应用中，你可能会在它们到达时处理它们。
//        这里，执行后的最终状态就是感兴趣的项目。

        for (var item : compiledGraph.stream( Map.of( SimpleState.MESSAGES_KEY, "Let's, begin!" ) ) ) {

            System.out.println( item );
        }

    }
}