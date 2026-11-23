import { computed, ref } from 'vue'
import { getResOrgTreeApi } from '@/api/res'
import type { ResOrgTreeNode } from '@/types/api'

/** 树拍平后的一行：depth 决定缩进，node 是原始节点 */
export interface FlatOrgRow {
  node: ResOrgTreeNode
  depth: number
}

/**
 * 资源目录左树（组织/设备/通道三页共用）。
 *
 * 选择语义：selectedId = null 表示"全部组织"，各页据此决定是否给接口传 orgId。
 * 刷新后按 id 找回选中节点，避免节点对象被整体替换后详情区展示过期计数。
 */
export const useResTree = () => {
  const tree = ref<ResOrgTreeNode[]>([])
  const loading = ref(false)
  const expandedIds = ref<Set<string>>(new Set())
  const selectedId = ref<string | null>(null)
  const initialized = ref(false)

  const collectIds = (list: ResOrgTreeNode[], acc: string[] = []): string[] => {
    list.forEach((n) => {
      acc.push(n.id)
      if (n.children?.length) collectIds(n.children, acc)
    })
    return acc
  }

  const findNodeIn = (list: ResOrgTreeNode[], id: string): ResOrgTreeNode | undefined => {
    for (const n of list) {
      if (n.id === id) return n
      if (n.children?.length) {
        const hit = findNodeIn(n.children, id)
        if (hit) return hit
      }
    }
  }

  const flatRows = computed<FlatOrgRow[]>(() => {
    const acc: FlatOrgRow[] = []
    const walk = (list: ResOrgTreeNode[], depth: number) => {
      for (const n of list) {
        acc.push({ node: n, depth })
        if (n.children?.length && expandedIds.value.has(n.id)) walk(n.children, depth + 1)
      }
    }
    walk(tree.value, 0)
    return acc
  })

  const allExpanded = computed(
    () =>
      flatRows.value.length > 0 &&
      collectIds(tree.value).every((id) => expandedIds.value.has(id)),
  )

  const selectedNode = computed<ResOrgTreeNode | null>(() =>
    selectedId.value ? (findNodeIn(tree.value, selectedId.value) ?? null) : null,
  )

  /** 节点直接归属设备/通道总和的概览（底部状态栏用） */
  const totals = computed(() => {
    let orgs = 0
    let devices = 0
    let channels = 0
    const walk = (list: ResOrgTreeNode[]) => {
      for (const n of list) {
        orgs += 1
        devices += n.deviceCount ?? 0
        channels += n.channelCount ?? 0
        if (n.children?.length) walk(n.children)
      }
    }
    walk(tree.value)
    return { orgs, devices, channels }
  })

  const fetchTree = async () => {
    loading.value = true
    try {
      tree.value = (await getResOrgTreeApi()).data ?? []
      if (!initialized.value) {
        expandedIds.value = new Set(collectIds(tree.value))
        initialized.value = true
      }
      // 选中的节点在刷新后可能已不存在（被同步标为消失），这时退回"全部"
      if (selectedId.value && !findNodeIn(tree.value, selectedId.value)) {
        selectedId.value = null
      }
    } finally {
      loading.value = false
    }
  }

  const toggle = (id: string) => {
    const next = new Set(expandedIds.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    expandedIds.value = next
  }

  const toggleAll = () => {
    expandedIds.value = allExpanded.value ? new Set() : new Set(collectIds(tree.value))
  }

  /** 展开到某个节点（设备/通道页从别处带 orgId 进来时用） */
  const expandTo = (id: string) => {
    const path: string[] = []
    const findPath = (list: ResOrgTreeNode[], trail: string[]): boolean => {
      for (const n of list) {
        const nextTrail = [...trail, n.id]
        if (n.id === id) {
          path.push(...nextTrail)
          return true
        }
        if (n.children?.length && findPath(n.children, nextTrail)) return true
      }
      return false
    }
    if (findPath(tree.value, [])) {
      const next = new Set(expandedIds.value)
      path.forEach((p) => next.add(p))
      expandedIds.value = next
    }
  }

  const select = (id: string | null) => {
    selectedId.value = id
    if (id) expandTo(id)
  }

  return {
    tree,
    loading,
    expandedIds,
    flatRows,
    allExpanded,
    selectedId,
    selectedNode,
    totals,
    fetchTree,
    toggle,
    toggleAll,
    select,
  }
}
