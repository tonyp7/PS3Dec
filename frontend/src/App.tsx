import { useState } from 'react'
import { ApiRequestError, api } from '@/api/client'
import type { Mode } from '@/api/types'
import { ConnectionBanner } from '@/components/ConnectionBanner'
import { IsoTable } from '@/components/IsoTable'
import { JobCard } from '@/components/JobCard'
import { OverwriteDialog } from '@/components/OverwriteDialog'
import { SelectionPanel } from '@/components/SelectionPanel'
import { useJob } from '@/hooks/useJob'
import { useResource } from '@/hooks/useResource'
import { defaultMode, isThreeK3y, startBlocker } from '@/lib/gate'

export default function App() {
  const isos = useResource(api.isos)
  const keys = useResource(api.keys)
  const { job, error: jobError, refresh: refreshJob } = useJob()

  const [selected, setSelected] = useState<string | null>(null)
  const [mode, setMode] = useState<Mode>('decrypt')
  const [keyName, setKeyName] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)

  const iso = isos.data?.items.find((i) => i.name === selected)
  const keyItems = keys.data?.items ?? []
  const running = job?.state === 'running'
  const blocker = startBlocker({ iso, mode, keyName, keys: keyItems, jobRunning: running })

  const select = (name: string) => {
    const item = isos.data?.items.find((i) => i.name === name)
    if (!item) return
    setSelected(name)
    setMode(defaultMode(item))
    setKeyName(item.suggested_key)
    setStartError(null)
  }

  const start = async (overwrite: boolean) => {
    if (!iso) return
    setStarting(true)
    setStartError(null)
    try {
      await api.start({
        iso: iso.name,
        mode,
        key: isThreeK3y(iso) ? undefined : (keyName ?? undefined),
        overwrite,
      })
      refreshJob()
    } catch (e) {
      if (e instanceof ApiRequestError) {
        if (e.code === 'output_exists' && !overwrite) setConfirmOpen(true)
        else {
          setStartError(e.message)
          if (e.code === 'job_running') refreshJob()
        }
      } else {
        setStartError('Could not reach the server. Try again in a moment.')
      }
    } finally {
      setStarting(false)
    }
  }

  const cancel = async () => {
    setCancelling(true)
    try {
      await api.cancel()
    } catch {
      /* the connection banner reports unreachable servers */
    } finally {
      setCancelling(false)
      refreshJob()
    }
  }

  const refreshLists = () => {
    isos.reload()
    keys.reload()
  }

  return (
    <main className="mx-auto flex max-w-4xl flex-col gap-4 p-4 md:p-6">
      <header>
        <h1 className="text-2xl font-semibold">PS3Dec</h1>
        <p className="text-sm text-muted-foreground">
          Decrypt and encrypt PS3 disc images. Files are read from and written to the mounted folders.
        </p>
      </header>

      <ConnectionBanner error={isos.error ?? keys.error ?? jobError} />

      <IsoTable
        listing={isos.data}
        selected={iso ? iso.name : null}
        onSelect={select}
        onRefresh={refreshLists}
        refreshing={isos.loading || keys.loading}
      />

      <SelectionPanel
        iso={iso}
        keys={keyItems}
        keysAvailable={keys.data?.available ?? true}
        keysFolder={keys.data?.folder ?? ''}
        mode={mode}
        onModeChange={(m) => {
          setMode(m)
          setStartError(null)
        }}
        keyName={keyName}
        onKeyChange={(k) => {
          setKeyName(k)
          setStartError(null)
        }}
        blocker={blocker}
        onStart={() => void start(false)}
        starting={starting}
        error={startError}
      />

      <JobCard job={job} onCancel={() => void cancel()} cancelling={cancelling} />

      <OverwriteDialog
        open={confirmOpen}
        name={iso?.name ?? ''}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => {
          setConfirmOpen(false)
          void start(true)
        }}
      />
    </main>
  )
}
