import type { IsoItem, KeyItem, Mode } from '@/api/types'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { isThreeK3y } from '@/lib/gate'

interface Props {
  iso: IsoItem | undefined
  keys: KeyItem[]
  keysAvailable: boolean
  keysFolder: string
  mode: Mode
  onModeChange: (mode: Mode) => void
  keyName: string | null
  onKeyChange: (key: string | null) => void
  blocker: string | null
  onStart: () => void
  starting: boolean
  error: string | null
}

export function SelectionPanel(p: Props) {
  const threeK3y = p.iso ? isThreeK3y(p.iso) : false
  return (
    <Card>
      <CardHeader>
        <CardTitle>Convert</CardTitle>
        <CardDescription>
          {p.iso ? (
            <>
              Selected: <span className="font-medium text-foreground">{p.iso.name}</span>
            </>
          ) : (
            'Select a disc image above.'
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <span id="mode-label" className="text-sm font-medium">
            Mode
          </span>
          <ToggleGroup
            aria-labelledby="mode-label"
            variant="outline"
            value={[p.mode]}
            onValueChange={(v) => {
              if (v[0]) p.onModeChange(v[0] as Mode)
            }}
          >
            <ToggleGroupItem value="decrypt">Decrypt</ToggleGroupItem>
            <ToggleGroupItem value="encrypt">Encrypt</ToggleGroupItem>
          </ToggleGroup>
        </div>

        <div className="flex flex-col gap-1.5">
          <span id="key-label" className="text-sm font-medium">
            Key (.dkey)
          </span>
          <Select
            value={threeK3y ? null : p.keyName}
            onValueChange={(v) => p.onKeyChange(v)}
            disabled={threeK3y || !p.iso}
          >
            <SelectTrigger aria-labelledby="key-label" className="w-full max-w-sm">
              <SelectValue placeholder={threeK3y ? 'Not needed' : 'Choose a key'} />
            </SelectTrigger>
            <SelectContent>
              {p.keys.map((k) => (
                <SelectItem key={k.name} value={k.name} disabled={!k.valid}>
                  {k.name}
                  {!k.valid && ' (invalid)'}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {threeK3y && (
            <p className="text-xs text-muted-foreground">3k3y images carry their own key, so no .dkey is needed.</p>
          )}
          {!threeK3y && !p.keysAvailable && (
            <p className="text-xs text-muted-foreground">
              The keys folder <code className="font-mono">{p.keysFolder}</code> is not available.
            </p>
          )}
          {!threeK3y && p.keysAvailable && p.keys.length === 0 && (
            <p className="text-xs text-muted-foreground">
              No <code className="font-mono">.dkey</code> files found in <code className="font-mono">{p.keysFolder}</code>.
            </p>
          )}
        </div>

        {p.error && (
          <Alert variant="destructive">
            <AlertDescription>{p.error}</AlertDescription>
          </Alert>
        )}
      </CardContent>
      <CardFooter className="flex items-center gap-3">
        <Button onClick={p.onStart} disabled={p.blocker !== null || p.starting}>
          {p.starting ? 'Starting…' : 'Start'}
        </Button>
        {p.blocker && <span className="text-sm text-muted-foreground">{p.blocker}</span>}
      </CardFooter>
    </Card>
  )
}
