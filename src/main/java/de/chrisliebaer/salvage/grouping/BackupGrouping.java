package de.chrisliebaer.salvage.grouping;

import com.google.common.graph.GraphBuilder;
import com.google.common.graph.ImmutableGraph;
import com.google.common.graph.SuccessorsFunction;
import com.google.common.graph.Traverser;
import de.chrisliebaer.salvage.entity.SalvageContainer;
import de.chrisliebaer.salvage.entity.SalvageTide;
import de.chrisliebaer.salvage.entity.SalvageVolume;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * This class is responsible for grouping volumes into separate groups for the given tide. It does so by building a dependency graph of containers and their used volumes.
 * The graph is then traversed to find strongly connected components (SCCs) and then the SCCs are grouped into groups. Each group contains a minimal set of containers
 * that need to be touched during backup. Depending on the grouping mode, traversal will be done while ignoring certain edges in the resulting graph, leading to different
 * backup groups. While each volume is guaranteed to be part of exactly one group, a container can be part of multiple groups, depending on the grouping mode.
 */
@Slf4j
public final class BackupGrouping {
	
	private final List<SalvageContainer> containers;
	private final Map<String, SalvageVolume> volumes;
	private final SalvageTide.GroupingMode groupingMode;
	
	private BackupGrouping(List<SalvageContainer> containers, Map<String, SalvageVolume> volumes, SalvageTide.GroupingMode groupingMode) {
		this.containers = containers;
		this.volumes = volumes;
		this.groupingMode = groupingMode;
	}
	
	private ImmutableGraph<Node> buildGraph() {
		var builder = GraphBuilder.undirected().<Node>immutable();
		
		// in project mode, nodes of same project will be connected via project node to force same group (in other modes, traversal will ignore edge)
		for (var container : containers) {
			container.project().ifPresent(s -> builder.putEdge(new ProjectNode(s), new ContainerNode(container)));
		}
		
		// add volume dependencies between containers and volumes
		for (var container : containers) {
			var containerNode = new ContainerNode(container);
			for (var volume : container.volumes()) {
				builder.putEdge(containerNode, new VolumeNode(volume));
			}
		}
		
		// some volumes might not be used by any container, so we add them as well
		for (var volume : volumes.values())
			builder.addNode(new VolumeNode(volume));
		
		return builder.build();
	}
	
	private List<Group> groups() {
		var graph = buildGraph();
		
		Predicate<Node> successorFilter = switch (groupingMode) {
			
			// only follow edges to containers
			case INDIVIDUAL -> node -> node instanceof ContainerNode;
			
			// do not connect containers via project nodes
			case SMART -> node -> !(node instanceof ProjectNode);
			
			// follow all edges
			case PROJECT -> node -> true;
		};
		
		// traverse graph starting at volume nodes
		var unvisited = new ArrayList<>(graph.nodes());
		var groups = new ArrayList<Group>();
		var traversal = Traverser.forGraph((SuccessorsFunction<Node>) node -> graph.successors(node).stream().filter(successorFilter)::iterator);
		while (!unvisited.isEmpty()) {
			var current = unvisited.remove(0);
			if (!(current instanceof VolumeNode))
				continue;
			
			var group = new Group();
			for (Node node : traversal.depthFirstPostOrder(current)) {
				node.add(group);
				unvisited.remove(node);
			}
			
			// note: we always start at a volume node, so each group will contain at least one volume
			groups.add(group);
		}
		
		return groups;
	}
	
	public static List<Group> groups(List<SalvageContainer> containers, Map<String, SalvageVolume> volumes, SalvageTide.GroupingMode groupingMode) {
		return new BackupGrouping(containers, volumes, groupingMode).groups();
	}
	
	@ToString
	@SuppressWarnings("InnerClassFieldHidesOuterClassField") // really don't care about this
	public static class Group {
		
		@Getter private final List<SalvageContainer> containers = new ArrayList<>();
		@Getter private final List<SalvageVolume> volumes = new ArrayList<>();
		
		private void addContainer(SalvageContainer container) {
			containers.add(container);
		}
		
		private void addVolume(SalvageVolume volume) {
			volumes.add(volume);
		}
	}
	
	private sealed interface Node {
		
		default void add(Group group) {}
	}
	
	private record ProjectNode(String name) implements Node {}
	
	private record ContainerNode(SalvageContainer container) implements Node {
		
		@Override
		public void add(Group group) {
			group.addContainer(container);
		}
	}
	
	private record VolumeNode(SalvageVolume volume) implements Node {
		
		@Override
		public void add(Group group) {
			group.addVolume(volume);
		}
	}
}
