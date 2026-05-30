package com.aryan.conduit.common.util;

import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.*;

@Component
public class DagValidator{
    public boolean hashCycle(Map<Long, List<Long>> graph){
        Set<Long> visited=new HashSet<>();
        Set<Long> recursionStack=new HashSet<>();

        for(Long node:graph.keySet()){
            if(dfs(node,graph,visited,recursionStack)){
                return  true;
            }
        }
        return false;
    }

    private boolean dfs(Long node,Map<Long,List<Long>> graph,Set<Long> visited,Set<Long> stack){
        if(stack.contains(node)){
            return true;
        }
        if(visited.contains(node)){
            return false;
        }
        visited.add(node);
        stack.add(node);

        for(Long neighbor:graph.getOrDefault(node, Collections.emptyList())){
            if(dfs(neighbor,graph,visited,stack)){
                return true;
            }
        }
        stack.remove(node);
        return false;
    }

    public List<Long> topologicalSort(Map<Long,List<Long>> graph){
        Map<Long,Integer> indegree=new HashMap<>();
        for(Long node:graph.keySet()){
            indegree.putIfAbsent(node,0);

            for(Long neighbor:graph.get(node)){
                indegree.put(neighbor,indegree.getOrDefault(neighbor,0)+1);
            }
        }
        Queue<Long> queue=new LinkedList<>();

        for(Map.Entry<Long,Integer> entry:
                indegree.entrySet()){
            if(entry.getValue()==0){
                queue.offer(entry.getKey());
            }
        }
        List<Long> result=new ArrayList<>();

        while (!queue.isEmpty()){
            Long current= queue.poll();
            result.add(current);

            for(Long neighbor:graph.getOrDefault(current,Collections.emptyList())){
                indegree.put(neighbor, indegree.get(neighbor)-1);

                if(indegree.get(neighbor)==0){
                    queue.offer(neighbor);
                }
            }
        }
        return result;
    }

}
