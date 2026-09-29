package com.guonl.flow.core.node;

import com.guonl.flow.core.model.NodeDefinition;
import com.guonl.flow.core.model.NodeType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 节点处理器注册表：按类型路由到对应Handler。
 */
@Component
public class NodeHandlerRegistry {

    private final Map<NodeType, NodeHandler> handlers = new EnumMap<>(NodeType.class);

    public NodeHandlerRegistry(List<NodeHandler> handlerList) {
        for (NodeHandler handler : handlerList) {
            handlers.put(handler.supports(), handler);
        }
    }

    public NodeHandler get(NodeType type) {
        NodeHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("节点类型未注册处理器: " + type);
        }
        return handler;
    }

    public NodeHandler get(NodeDefinition node) {
        return get(node.getType());
    }
}
